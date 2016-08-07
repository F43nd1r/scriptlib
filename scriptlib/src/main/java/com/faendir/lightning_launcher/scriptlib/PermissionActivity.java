package com.faendir.lightning_launcher.scriptlib;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;

import java.util.HashMap;

/**
 * Created by Lukas on 17.11.2015.
 * Because android requires an Activity to be able to request permissions, and we don't want to put this code in every activity,
 * we need this activity. This activity should never be started from outside. Instead, call the static Method.
 */
public class PermissionActivity extends Activity {
    private static final String ID = "id";
    private static final String PERMISSION = "permission";

    private static final HashMap<Integer, PermissionCallback> callbacks = new HashMap<>();
    private static int nextId = 0;

    public static void checkForPermission(@NonNull Context context, String permission, PermissionCallback callback) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                int id = nextId++;
                callbacks.put(id, callback);
                Intent intent = new Intent(context, PermissionActivity.class);
                intent.putExtra(ID, id);
                intent.putExtra(PERMISSION, permission);
                if (!(context instanceof Activity)) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                context.startActivity(intent);
            } else {
                callback.handlePermissionResult(true);
            }
        } else {
            callback.handlePermissionResult(context.checkCallingOrSelfPermission(permission) == PackageManager.PERMISSION_GRANTED);
        }
    }

    private int id;
    private String permission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        if (intent.hasExtra(ID) && intent.hasExtra(PERMISSION)) {
            id = intent.getIntExtra(ID, -1);
            permission = intent.getStringExtra(PERMISSION);
            requestPermission();
        } else finish();
    }

    @TargetApi(Build.VERSION_CODES.M)
    private void requestPermission() {
        requestPermissions(new String[]{permission}, 0);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        callbacks.get(id).handlePermissionResult(grantResults.length == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED);
        callbacks.remove(id);
        finish();
    }

    public interface PermissionCallback {
        void handlePermissionResult(boolean isGranted);
    }
}
