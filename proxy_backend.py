import os, time, csv, requests, psutil, json
import sys
from datetime import datetime
from concurrent.futures import ThreadPoolExecutor, as_completed

AUTH_BASE   = "http://localhost:8080"
BASE_URL    = f"{AUTH_BASE}/user"
LOGIN_PATH  = "/login"
USERNAME    = "tan1"
PASSWORD    = "1"
TEST_DIR    = r"D:\UET\KTPM\Valet Key\test_files\New folder"
RESULTS_CSV = f"results_proxy_{datetime.now().strftime('%Y%m%d_%H%M%S')}.csv"
CONCURRENCY_SET = [1, 2, 4, 6, 8]  
MAX_CONCURRENCY = 30


def login_and_get_jsessionid():
    s = requests.Session()
    r = s.post(f"{AUTH_BASE}{LOGIN_PATH}", json={"username": USERNAME, "password": PASSWORD}, timeout=30)
    r.raise_for_status()
    sid = s.cookies.get("JSESSIONID")
    if not sid: raise RuntimeError("No JSESSIONID after login")
    return sid

def upload_one(sid, p):
    name = os.path.basename(p)
    # đo client trước/sau như bạn đang làm
    cpu_before = psutil.cpu_percent(interval=0.1)
    mem_before_mb = psutil.virtual_memory().used / (1024*1024)
    t0 = time.time()
    with open(p, "rb") as f:
        r = requests.post(
            f"{BASE_URL}/proxy-upload",
            files={"file": (name, f)},
            data={"fileName": name},
            headers={"Cookie": f"JSESSIONID={sid}"},
            timeout=600
        )
    t = time.time() - t0
    cpu_after = psutil.cpu_percent(interval=0.1)
    mem_after_mb = psutil.virtual_memory().used / (1024*1024)
    cpu_avg = round((cpu_before + cpu_after) / 2, 1)
    mem_avg = round((mem_before_mb + mem_after_mb) / 2, 1)

    size_mb = os.path.getsize(p)/(1024*1024)
    thr = round(size_mb / t, 2) if t > 0 else 0.0
    ok = (r.status_code == 200)
    server_time = server_cpu = server_mem = ""
    if ok:
        try:
            j = r.json()
            server_time = j.get("serverTime_s", "")
            server_cpu  = j.get("serverCPU_pct", "")
            server_mem  = j.get("serverMemory_MB", "")
        except Exception:
            pass
    return (name, round(t,2), round(size_mb,2), thr, 0 if ok else 1, cpu_avg, mem_avg, server_time, server_cpu, server_mem, r.status_code)

def run_with_concurrency(sid, files, conc, writer):
    with ThreadPoolExecutor(max_workers=conc) as ex:
        futs = [ex.submit(upload_one, sid, p) for p in files]
        for fut in as_completed(futs):
            name, t, size, thr, retryfail, cpu_cli, mem_cli, st, scpu, smem, code = fut.result()
            # Match full header schema
            writer.writerow(["PROXY", t, size, thr, name, conc, scpu, smem])
            print(f"[{conc}] {name}: {thr} MB/s")

def main():
    if not os.path.isdir(TEST_DIR):
        print(" TEST_DIR not found"); return
    sid = login_and_get_jsessionid()
    files = [os.path.join(TEST_DIR, n) for n in sorted(os.listdir(TEST_DIR)) if os.path.isfile(os.path.join(TEST_DIR, n))]
    # Cho phép set thủ công concurrency
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
    out = f"results_proxy_{datetime.now().strftime('%Y%m%d_%H%M%S')}.csv"
    with open(out, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        # Full header including RetryFail and client CPU/Memory
        w.writerow(["Type","TotalTime_s","TotalBytes_MB","Throughput_MBps","File","Concurrency","ServerCPU_pct","ServerMemory_MB"])
        for conc in conc_list:
            print(f"\n=== CONCURRENCY={conc} ===")
            run_with_concurrency(sid, files, conc, w)
    print(f"Saved: {out}")

if __name__ == "__main__":
    main()
