import os
import time
import csv
import requests
import subprocess
import sys
from datetime import datetime
from concurrent.futures import ThreadPoolExecutor, as_completed

# ===== CONFIG =====
BASE_URL = "http://localhost:8080"               
SAS_ENDPOINT = "/user/upload-sas"                       
TEST_FOLDER = r"D:\UET\KTPM\Valet Key\test_files\New folder"   
RESULTS_FILE = f"results_azcopy_{datetime.now().strftime('%Y%m%d_%H%M%S')}.csv"



LOGIN_PATH = "/login"                               
USERNAME = "demo"
PASSWORD = "1"


USE_AZCOPY = True
AZCOPY_EXE = "azcopy"            
AZCOPY_CONCURRENCY = 32            
AZCOPY_BLOCK_SIZE_MB = 8         
AZCOPY_CAP_MBPS = 0               
AZCOPY_PUT_MD5 = False             

CONCURRENCY_SET = [1, 2, 4, 6, 8]
MAX_CONCURRENCY = 30

def get_session():
    s = requests.Session()
    if USE_EXISTING_COOKIE:
        s.cookies.set("JSESSIONID", JSESSIONID, domain="localhost")
        return s
    resp = s.post(f"{BASE_URL}{LOGIN_PATH}", json={"username": USERNAME, "password": PASSWORD}, timeout=30)
    resp.raise_for_status()
    if "JSESSIONID" not in s.cookies:
        raise RuntimeError("Login OK but JSESSIONID cookie not found. Adjust LOGIN_PATH/payload.")
    return s

def request_sas_for_file(session: requests.Session, blob_name: str) -> str:
    # Most Spring controllers accept blobName via query param for @RequestParam
    resp = session.post(f"{BASE_URL}{SAS_ENDPOINT}", params={"blobName": blob_name}, timeout=60)
    if resp.status_code != 200:
        raise RuntimeError(f"SAS request failed {resp.status_code}: {resp.text}")
    data = resp.json()
    sas_url = data.get("sasUrl")
    if not sas_url:
        raise RuntimeError(f"No sasUrl in response: {data}")
    return sas_url

def upload_with_sas(file_path: str, sas_url: str) -> tuple[float, bool]:
    start = time.time()
    with open(file_path, "rb") as f:
        # Minimum required headers for Azure Block Blob single-shot upload
        headers = {
            "x-ms-blob-type": "BlockBlob",
            "Content-Type": "application/octet-stream",
        }
        put = requests.put(sas_url, data=f, headers=headers, timeout=60*30)
    elapsed = time.time() - start
    ok = put.status_code in (200, 201)
    return elapsed, ok

def upload_with_azcopy(file_path: str, sas_url: str) -> tuple[float, bool]:
    base_args = [AZCOPY_EXE, "cp", file_path, sas_url, "--overwrite=true", "--check-length=true"]
    tuned_args = base_args + [
        f"--concurrency-value={AZCOPY_CONCURRENCY}",
        f"--block-size-mb={AZCOPY_BLOCK_SIZE_MB}",
        "--from-to=LocalBlob",
    ]
    if AZCOPY_CAP_MBPS > 0:
        tuned_args.append(f"--cap-mbps={AZCOPY_CAP_MBPS}")
    if AZCOPY_PUT_MD5:
        tuned_args.append("--put-md5")

    start = time.time()
    result = subprocess.run(tuned_args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if "unknown flag" in (result.stderr or "").lower():
        result = subprocess.run(base_args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    elapsed = time.time() - start
    ok = (result.returncode == 0)
    return elapsed, ok

def upload_one(session: requests.Session, file_path: str):
    file_name = os.path.basename(file_path)
    size_mb = os.path.getsize(file_path) / (1024*1024)
    try:
        start_total = time.time()  # bắt đầu đo tổng thời gian
        sas_url = request_sas_for_file(session, file_name)
        if USE_AZCOPY:
            elapsed_upload, ok = upload_with_azcopy(file_path, sas_url)
        else:
            elapsed_upload, ok = upload_with_sas(file_path, sas_url)
        elapsed_total = time.time() - start_total  # tổng thời gian
        throughput = round(size_mb / elapsed_total, 2) if elapsed_total > 0 else 0.0
        return (file_name, round(elapsed_total, 2), round(size_mb, 2), throughput, 0 if ok else 1)
    except Exception as e:
        return (file_name, 0, round(size_mb, 2), 0, 1)


def run_with_concurrency(session: requests.Session, files: list[str], conc: int, writer: csv.writer):
    with ThreadPoolExecutor(max_workers=conc) as ex:
        futures = [ex.submit(upload_one, session, p) for p in files]
        for fut in as_completed(futures):
            file_name, elapsed, size_mb, throughput, retryfail = fut.result()
            writer.writerow([
                "SAS",
                elapsed,
                size_mb,
                throughput,
                file_name,
                conc
            ])
            print(f"[{conc}] {file_name}: {throughput} MB/s")

def main():
    session = get_session()

    os.makedirs(os.path.dirname(RESULTS_FILE) or ".", exist_ok=True)
    files = [os.path.join(TEST_FOLDER, name) for name in os.listdir(TEST_FOLDER) if os.path.isfile(os.path.join(TEST_FOLDER, name))]
    files.sort()

    # Allow manual concurrency set via argv or prompt
    user_inp = None
    if len(sys.argv) > 1:
        user_inp = sys.argv[1]
    else:
        try:
            user_inp = input("Enter concurrency (e.g., 1,2,4) or single number, blank=default [1,2,4,6,8]: ").strip()
        except Exception:
            user_inp = None
    conc_list = CONCURRENCY_SET
    if user_inp:
        try:
            if "," in user_inp:
                conc_list = [min(MAX_CONCURRENCY, max(1, int(x))) for x in user_inp.split(',') if x.strip()]
            else:
                conc_list = [min(MAX_CONCURRENCY, max(1, int(user_inp)))]
        except ValueError:
            print("  Invalid concurrency input, using default.")
            conc_list = CONCURRENCY_SET

    with open(RESULTS_FILE, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        # Columns with Concurrency
        writer.writerow(["Type","TotalTime_s","TotalBytes_MB","Throughput_MBps","Filename","CPU_Proxy"])
        for conc in conc_list:
            print(f"\n=== CONCURRENCY={conc} ===")
            run_with_concurrency(session, files, conc, writer)

    print(f"\nSaved results to {RESULTS_FILE}")

if __name__ == "__main__":
    main()