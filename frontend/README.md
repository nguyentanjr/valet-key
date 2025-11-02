# Cloud Storage Frontend

A modern React frontend for the Cloud Storage application.

## Features

- 🔐 User authentication (login/logout)
- 📁 Folder management (create, navigate, breadcrumbs)
- 📂 File operations (upload, download, delete, rename, move)
- 🔍 File search
- 🔗 Public file sharing
- 💾 Storage quota display
- 📱 Responsive design

## Getting Started

### Prerequisites

- Node.js 16+ and npm
- Backend server running on http://localhost:8080

### Installation

```bash
# Navigate to frontend directory
cd frontend

# Install dependencies
npm install

# Start development server
npm start
```

The application will open at http://localhost:3000

### Build for Production

```bash
npm run build
```

This creates an optimized build in the `build` folder.

## Project Structure

```
frontend/
├── public/
│   └── index.html
├── src/
│   ├── components/
│   │   ├── Dashboard.js          # Main dashboard
│   │   ├── Dashboard.css
│   │   ├── Login.js              # Login page
│   │   ├── Login.css
│   │   ├── FileList.js           # File list with actions
│   │   ├── FileList.css
│   │   ├── FileUpload.js         # File upload component
│   │   ├── FileUpload.css
│   │   ├── PublicFile.js         # Public file sharing view
│   │   └── PublicFile.css
│   ├── services/
│   │   └── api.js                # API client
│   ├── App.js                    # Main app component
│   ├── App.css                   # Global styles
│   └── index.js                  # Entry point
├── package.json
└── README.md
```

## Features Overview

### Authentication
- Login with username/password
- Session-based authentication
- Automatic session check on load

### File Management
- Upload files to current folder
- Download files via temporary SAS URLs
- Delete files
- Rename files
- Move files to different folders
- Search files by name

### Folder Management
- Create nested folders
- Navigate folder hierarchy
- Breadcrumb navigation
- Visual folder grid

### Public Sharing
- Generate public links for files
- Copy link to clipboard
- Anyone with link can download
- Access public files without login

### Storage Management
- Real-time storage quota display
- Visual progress bar
- Storage usage in header

## API Integration

The frontend communicates with the backend via REST API:

- **Base URL**: http://localhost:8080
- **Authentication**: Session cookies (withCredentials: true)
- **CORS**: Enabled for http://localhost:3000

All API calls are in `src/services/api.js`:

```javascript
import { authAPI, fileAPI, folderAPI, publicAPI } from './services/api';

// Login
await authAPI.login(username, password);

// Upload file
await fileAPI.upload(file, folderId);

// Create folder
await folderAPI.create(folderName, parentFolderId);
```

## Styling

- Modern gradient design (purple/blue theme)
- Responsive layout (mobile-friendly)
- Clean card-based UI
- Icon integration (react-icons)
- Smooth transitions and hover effects

## Key Components

### Dashboard
Main application view with:
- Header with storage info
- Breadcrumb navigation
- Folder grid
- File upload area
- File list table
- Search functionality

### FileList
Table view of files with:
- Download button
- Rename modal
- Move modal
- Share modal (public links)
- Delete confirmation

### FileUpload
Drag & drop style upload area:
- File selection
- Upload progress
- File size validation

### PublicFile
Public file sharing page:
- No authentication required
- File information display
- Direct download button
- Clean, minimal design

## Environment Variables

The app uses proxy configuration in `package.json`:

```json
"proxy": "http://localhost:8080"
```

This proxies API requests to the backend server.

## Browser Support

- Chrome (latest)
- Firefox (latest)
- Safari (latest)
- Edge (latest)

## Troubleshooting

### CORS Issues
Make sure backend has CORS enabled for http://localhost:3000

### Session Not Persisting
Check that `withCredentials: true` is set in axios config

### Files Not Uploading
- Check backend is running
- Verify file size limits
- Check storage quota

### Public Links Not Working
- Ensure token is correct
- Check backend public endpoints are accessible
- Verify file is still shared

## Development Tips

1. **Hot Reload**: Changes auto-reload during development
2. **Console Logs**: Check browser console for errors
3. **Network Tab**: Inspect API calls in DevTools
4. **React DevTools**: Install React DevTools extension

## Future Enhancements

- [ ] Drag & drop file upload
- [ ] Bulk file operations
- [ ] File preview (images, PDFs)
- [ ] Progress tracking for large uploads
- [ ] Dark mode
- [ ] File sharing with expiry dates
- [ ] Folder sharing

## License

This project is for educational purposes.

