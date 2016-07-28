package com.faendir.lightning_launcher.scriptlib;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.support.annotation.CheckResult;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.faendir.lightning_launcher.scriptlib.exception.PermissionNotGrantedException;
import com.faendir.lightning_launcher.scriptlib.exception.RepositoryImporterException;
import com.faendir.lightning_launcher.scriptlib.exception.RepositoryImporterMissingException;
import com.faendir.lightning_launcher.scriptlib.exception.RepositoryImporterOutdatedException;
import com.trianguloy.llscript.repository.aidl.Failure;
import com.trianguloy.llscript.repository.aidl.IImportCallback;
import com.trianguloy.llscript.repository.aidl.ILightningService;
import com.trianguloy.llscript.repository.aidl.IResultCallback;
import com.trianguloy.llscript.repository.aidl.Script;

import static com.faendir.lightning_launcher.scriptlib.ScriptManager.logger;

/**
 * Created on 01.06.2016.
 *
 * @author F43nd1r
 */
public class ServiceManager {

    private static final String INTENT = "net.pierrox.lightning_launcher.script.IMPORT";
    private static final int MIN_SERVICE_VERSION = 30;

    private final ServiceInfo serviceInfo;
    private final int version;
    private final Context context;
    private ResponseManager responseManager;
    private final ImporterConnection connection = new ImporterConnection();
    private boolean isBinding = false;

    ServiceManager(Context context, ResponseManager responseManager) {
        this.context = context;
        this.responseManager = responseManager;
        logger.log("Resolving service...");
        Intent service = new Intent(INTENT);
        ResolveInfo info = context.getPackageManager().resolveService(service, 0);
        int version = -1;
        ServiceInfo serviceInfo = null;
        if (info != null) {
            logger.log("Service resolved");
            try {
                PackageInfo packageInfo = context.getPackageManager().getPackageInfo(info.serviceInfo.packageName, 0);
                version = packageInfo.versionCode;
                logger.log("Service version: " + version);
                serviceInfo = info.serviceInfo;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        } else {
            logger.log("Service not resolved");
        }
        this.version = version;
        this.serviceInfo = serviceInfo;
    }

    private class PermissionCallback implements PermissionActivity.PermissionCallback {
        private boolean isGranted = false;

        @Override
        public void handlePermissionResult(boolean isGranted) {
            if (isGranted) {
                this.isGranted = true;
                synchronized (this) {
                    notify();
                }
                logger.log("Permission granted");
                Intent intent = new Intent(INTENT);
                intent.setClassName(serviceInfo.packageName, serviceInfo.name);
                logger.log("Binding service...");
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            } else {
                isBinding = false;
                synchronized (this) {
                    notify();
                }
            }
        }
    }

    synchronized void bind() throws RepositoryImporterException {
        if (version >= MIN_SERVICE_VERSION) {
            if (connection.getService() == null && !isBinding) {
                isBinding = true;
                final PermissionCallback callback = new PermissionCallback();
                logger.log("Checking for permission...");
                PermissionActivity.checkForPermission(context, "net.pierrox.lightning_launcher.IMPORT_SCRIPTS", callback);
                //noinspection SynchronizationOnLocalVariableOrMethodParameter
                synchronized (callback) {
                    try {
                        if (!callback.isGranted) {
                            callback.wait();
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    if (!callback.isGranted) {
                        throw new PermissionNotGrantedException();
                    }
                }
            }
        } else if (serviceInfo != null) {
            throw new RepositoryImporterOutdatedException();
        } else {
            throw new RepositoryImporterMissingException();
        }
    }

    void unbind() {
        try {
            context.unbindService(connection);
        } catch (IllegalArgumentException e) {
            logger.warn("Trying to unbind while not bound.");
        }
    }

    private void enforceBoundOrBinding() {
        if (connection.getService() == null && !isBinding) {
            throw new IllegalStateException("You have to bind before you can call anything else.");
        }
    }

    private final class ImporterConnection implements ServiceConnection {
        private ILightningService service;

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            service = ILightningService.Stub.asInterface(iBinder);
            isBinding = false;
            logger.log("Service connected");
            synchronized (this) {
                notifyAll();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            logger.log("Service disconnected");
            service = null;
        }

        synchronized ILightningService getService() {
            return service;
        }
    }

    private ILightningService getService() {
        synchronized (connection) {
            if (connection.getService() != null) {
                logger.log("Service already bound, return directly");
                return connection.getService();
            }
            enforceBoundOrBinding();
            logger.log("Service binding, wait");
            try {
                connection.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        return connection.getService();
    }

    private final class ImportCallback extends IImportCallback.Stub {
        private final Script script;
        private int id = -1;

        ImportCallback(Script script) {
            this.script = script;
        }

        @Override
        public void onFinish(final int scriptId) throws RemoteException {
            id = scriptId;
            synchronized (this) {
                notify();
            }
        }

        @Override
        public void onFailure(Failure failure) throws RemoteException {
            switch (failure) {
                case SCRIPT_ALREADY_EXISTS:
                    responseManager.confirmUpdate(new ResultReceiver(new Handler(context.getMainLooper())) {
                        @Override
                        protected void onReceiveResult(int resultCode, Bundle resultData) {
                            if (resultCode == AlertDialog.BUTTON_POSITIVE) {
                                logger.log("User granted update");
                                id = loadScript(script, true);
                            } else {
                                logger.log("User denied update");
                            }
                            synchronized (ImportCallback.this) {
                                ImportCallback.this.notify();
                            }
                        }
                    });
                    break;
                case LAUNCHER_INVALID:
                case INVALID_INPUT:
                case EVAL_FAILED:
                    synchronized (this) {
                        notify();
                    }
                    break;
            }
        }

        int getId() {
            return id;
        }
    }

    @CheckResult
    public synchronized int loadScript(@NonNull final Script script, final boolean forceUpdate) {
        ILightningService service = getService();
        final ImportCallback callback = new ImportCallback(script);
        try {
            logger.log("Importing into LL...");
            service.importScript(script, forceUpdate, callback);
            //noinspection SynchronizationOnLocalVariableOrMethodParameter
            synchronized (callback) {
                callback.wait();
            }
            logger.log("Import finished");
            return callback.getId();
        } catch (SecurityException e) {
            e.printStackTrace();
            logger.log("SecurityException when calling service.");
        } catch (RemoteException | InterruptedException e) {
            e.printStackTrace();
        }
        return -1;
    }

    public synchronized void runScript(final int id, @Nullable final String data, final boolean background) {
        if (id < 0)
            logger.warn("Running script with negative id. Are you sure this is what you want to do?");
        try {
            getService().runScript(id, data, background);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private final class ResultCallback extends IResultCallback.Stub {
        private String result = null;

        @Override
        public void onResult(String result) throws RemoteException {
            this.result = result;
            synchronized (this) {
                notify();
            }
        }

        @Override
        public void onFailure(Failure failure) throws RemoteException {
            if (failure == Failure.EVAL_FAILED) {
                logger.warn("Script could not be evaluated");
            }
            synchronized (this) {
                notify();
            }
        }

        String getResult() {
            return result;
        }
    }

    public synchronized String runScriptForResult(@NonNull final String code) {
        final ResultCallback callback = new ResultCallback();
        try {
            getService().runScriptForResult(code, callback);
            //noinspection SynchronizationOnLocalVariableOrMethodParameter
            synchronized (callback) {
                callback.wait();
            }
            logger.log("Result received");
            return callback.getResult();
        } catch (RemoteException | InterruptedException e) {
            e.printStackTrace();
        }
        return null;
    }

    public synchronized void runAction(@Action final int actionId, @Nullable final String data, final boolean background) {
        try {
            getService().runAction(actionId, data, background);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    void setResponseManager(ResponseManager responseManager) {
        this.responseManager = responseManager;
    }

    public Context getContext() {
        return context;
    }
}
