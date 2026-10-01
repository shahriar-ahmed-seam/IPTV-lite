const { app, BrowserWindow, ipcMain, screen } = require('electron');
const path = require('path');

let mainWindow;
let pipWindow = null;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 900,
    minHeight: 600,
    frame: false,           // Frameless for custom title bar
    transparent: false,
    backgroundColor: '#0E0A1F',
    webPreferences: {
      nodeIntegration: true,
      contextIsolation: false,
      webSecurity: false     // Allow loading cross-origin HLS streams
    },
    icon: path.join(__dirname, 'assets', 'icon.png'),
    show: false
  });

  mainWindow.loadFile('index.html');

  mainWindow.once('ready-to-show', () => {
    mainWindow.show();
  });

  mainWindow.on('enter-full-screen', () => {
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send('window-fullscreen-state', true);
    }
  });

  mainWindow.on('leave-full-screen', () => {
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send('window-fullscreen-state', false);
    }
  });

  mainWindow.on('closed', () => {
    mainWindow = null;
    if (pipWindow && !pipWindow.isDestroyed()) {
      pipWindow.close();
      pipWindow = null;
    }
  });
}

// ============================================================
// Window control IPC handlers
// ============================================================
ipcMain.on('window-minimize', () => {
  if (mainWindow && !mainWindow.isDestroyed()) mainWindow.minimize();
});

ipcMain.on('window-maximize', () => {
  if (mainWindow && !mainWindow.isDestroyed()) {
    if (mainWindow.isMaximized()) {
      mainWindow.unmaximize();
    } else {
      mainWindow.maximize();
    }
  }
});

ipcMain.on('window-fullscreen-toggle', (event) => {
  if (mainWindow && !mainWindow.isDestroyed()) {
    const nextState = !mainWindow.isFullScreen();
    mainWindow.setFullScreen(nextState);
    event.returnValue = nextState;
  } else {
    event.returnValue = false;
  }
});

ipcMain.on('window-is-fullscreen', (event) => {
  event.returnValue = (mainWindow && !mainWindow.isDestroyed()) ? mainWindow.isFullScreen() : false;
});

ipcMain.on('window-close', () => {
  if (mainWindow && !mainWindow.isDestroyed()) mainWindow.close();
});

ipcMain.on('window-is-maximized', (event) => {
  event.returnValue = (mainWindow && !mainWindow.isDestroyed()) ? mainWindow.isMaximized() : false;
});

// Send the userData path to the renderer
ipcMain.on('get-user-data-path', (event) => {
  event.returnValue = app.getPath('userData');
});

// ============================================================
// Pop-up / Picture-in-Picture Resizable Window
// ============================================================
ipcMain.on('open-pip', (event, data) => {
  if (pipWindow && !pipWindow.isDestroyed()) {
    pipWindow.webContents.send('load-channel', data);
    pipWindow.show();
    pipWindow.focus();
  } else {
    const display = mainWindow && !mainWindow.isDestroyed()
      ? screen.getDisplayMatching(mainWindow.getBounds())
      : screen.getPrimaryDisplay();
    const workArea = display.workArea;

    const pipWidth = 480;
    const pipHeight = 280;
    const pipX = Math.round(workArea.x + workArea.width - pipWidth - 24);
    const pipY = Math.round(workArea.y + workArea.height - pipHeight - 24);

    pipWindow = new BrowserWindow({
      x: pipX,
      y: pipY,
      width: pipWidth,
      height: pipHeight,
      minWidth: 280,
      minHeight: 180,
      frame: false,
      alwaysOnTop: true,
      resizable: true,
      skipTaskbar: false,
      backgroundColor: '#0A0612',
      webPreferences: {
        nodeIntegration: true,
        contextIsolation: false,
        webSecurity: false
      },
      icon: path.join(__dirname, 'assets', 'icon.png'),
      show: false
    });

    pipWindow.loadFile('pip.html');

    pipWindow.once('ready-to-show', () => {
      pipWindow.show();
      pipWindow.webContents.send('load-channel', data);
    });

    pipWindow.on('closed', () => {
      pipWindow = null;
    });
  }

  if (data && data.minimizeMain && mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.minimize();
  }
});

ipcMain.on('pip-close', () => {
  if (pipWindow && !pipWindow.isDestroyed()) {
    pipWindow.close();
    pipWindow = null;
  }
});

ipcMain.on('pip-restore-main', (event, data) => {
  if (mainWindow && !mainWindow.isDestroyed()) {
    if (mainWindow.isMinimized()) {
      mainWindow.restore();
    }
    mainWindow.show();
    mainWindow.focus();
    if (data) {
      mainWindow.webContents.send('resume-from-pip', data);
    }
  }
  if (pipWindow && !pipWindow.isDestroyed()) {
    pipWindow.close();
    pipWindow = null;
  }
});

ipcMain.on('pip-minimize-main', () => {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.minimize();
  }
});

ipcMain.on('pip-toggle-pin', (event) => {
  if (pipWindow && !pipWindow.isDestroyed()) {
    const isTop = !pipWindow.isAlwaysOnTop();
    pipWindow.setAlwaysOnTop(isTop);
    event.returnValue = isTop;
  } else {
    event.returnValue = false;
  }
});

ipcMain.on('pip-is-pinned', (event) => {
  event.returnValue = (pipWindow && !pipWindow.isDestroyed()) ? pipWindow.isAlwaysOnTop() : true;
});

app.whenReady().then(createWindow);

app.on('window-all-closed', () => {
  app.quit();
});

app.on('activate', () => {
  if (BrowserWindow.getAllWindows().length === 0) {
    createWindow();
  }
});
