/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.camera.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Camera;

import com.android.camera.CameraActivity;
import com.android.camera.app.AppController;
import com.android.camera.app.ModuleManagerImpl;
import com.android.camera.debug.Log;
import com.android.camera.module.ModuleController;
import com.android.camera.util.CameraUtil;
import com.android.camera2.R;
import com.android.ex.camera2.portability.CameraAgentFactory;
import com.android.ex.camera2.portability.CameraDeviceInfo;
import com.android.ex.camera2.portability.Size;

import java.util.List;
import java.util.Map;

/**
 * Defines the AOSP upgrade path.
 */
public class UpgradeAosp {
    private static final Log.Tag TAG = new Log.Tag("UpgradeAosp");

    private static final String OLD_CAMERA_PREFERENCES_PREFIX = "_preferences_";
    private static final String OLD_MODULE_PREFERENCES_PREFIX = "_preferences_module_";

    /**
     * With this version everyone was forced to choose their location
     * settings again.
     */
    private static final int FORCE_LOCATION_CHOICE_VERSION = 2;

    /**
     * With this version, the camera size setting changed from a "small",
     * "medium" and "default" to strings representing the actual resolutions, i.e.
     * "1080x1776".
     */
    private static final int CAMERA_SIZE_SETTING_UPGRADE_VERSION = 3;

    /**
     * With this version, the names of the files storing camera specific
     * and module specific settings changed.
     */
    private static final int CAMERA_MODULE_SETTINGS_FILES_RENAMED_VERSION = 4;

    /**
     * With this version, timelapse mode was removed and mode indices need
     * to be resequenced.
     */
    private static final int CAMERA_SETTINGS_SELECTED_MODULE_INDEX = 5;

    /**
     * Increment this value whenever new AOSP UpgradeSteps need to
     * be executed.
     */
    public static final int AOSP_UPGRADE_VERSION = 5;

    /**
     * Returns UpgradeSteps which executes AOSP upgrade logic.
     */
    public static Upgrade.UpgradeSteps getAospUpgradeSteps(final AppController app) {
        return new Upgrade.UpgradeSteps() {
            @Override
            public void upgrade(SettingsManager settingsManager, int version) {
                if (version < FORCE_LOCATION_CHOICE_VERSION) {
                    forceLocationChoice(settingsManager);
                }

                if (version < CAMERA_SIZE_SETTING_UPGRADE_VERSION) {
                    CameraDeviceInfo infos = CameraAgentFactory
                            .getAndroidCameraAgent(app.getAndroidContext()).getCameraDeviceInfo();
                    upgradeCameraSizeSetting(settingsManager, app.getAndroidContext(), infos,
                                             SettingsUtil.CAMERA_FACING_FRONT);
                    upgradeCameraSizeSetting(settingsManager, app.getAndroidContext(), infos,
                                             SettingsUtil.CAMERA_FACING_BACK);
                }

                if (version < CAMERA_MODULE_SETTINGS_FILES_RENAMED_VERSION) {
                    upgradeCameraSettingsFiles(settingsManager, app.getAndroidContext());
                    upgradeModuleSettingsFiles(settingsManager, app.getAndroidContext(), app);
                    settingsManager.remove(SettingsManager.SCOPE_GLOBAL,
                            Keys.KEY_STARTUP_MODULE_INDEX);
                }

                if (version < CAMERA_SETTINGS_SELECTED_MODULE_INDEX) {
                    upgradeSelectedModeIndex(settingsManager, app.getAndroidContext());
                }
            }
        };
    }

    /**
     * Part of the AOSP upgrade path, forces the under to choose
     * their location again if it was originally set to false.
     */
    private static void forceLocationChoice(SettingsManager settingsManager) {
        // Show the location dialog on upgrade if
        //  (a) the user has never set this option (status quo).
        //  (b) the user opt'ed out previously.
        if (settingsManager.isSet(SettingsManager.SCOPE_GLOBAL,
                                  Keys.KEY_RECORD_LOCATION)) {
            // Location is set in the source file defined for this setting.
            // Remove the setting if the value is false to launch the dialog.
            if (!settingsManager.getBoolean(SettingsManager.SCOPE_GLOBAL,
                                            Keys.KEY_RECORD_LOCATION)) {
                settingsManager.remove(SettingsManager.SCOPE_GLOBAL, Keys.KEY_RECORD_LOCATION);
            }
        }
    }

    /**
     * Part of the AOSP upgrade path, sets front and back picture
     * sizes.
     */
    private static void upgradeCameraSizeSetting(SettingsManager settingsManager,
                                                Context context, CameraDeviceInfo infos,
                                                SettingsUtil.CameraDeviceSelector facing) {
        String key;
        if (facing == SettingsUtil.CAMERA_FACING_FRONT) {
            key = Keys.KEY_PICTURE_SIZE_FRONT;
        } else if (facing == SettingsUtil.CAMERA_FACING_BACK) {
            key = Keys.KEY_PICTURE_SIZE_BACK;
        } else {
            Log.w(TAG, "Ignoring attempt to upgrade size of unhandled camera facing direction");
            return;
        }

        String pictureSize = settingsManager.getString(SettingsManager.SCOPE_GLOBAL, key);
        int camera = SettingsUtil.getCameraId(infos, facing);
        if (camera != -1) {
            List<Size> supported = CameraPictureSizesCacher.getSizesForCamera(camera, context);
            if (supported != null) {
                Size size = SettingsUtil.getPhotoSize(pictureSize, supported, camera);
                settingsManager.set(SettingsManager.SCOPE_GLOBAL, key,
                                    SettingsUtil.sizeToSetting(size));
            }
        }
    }
    /**
     * Part of the AOSP upgrade path, copies all of the keys and values in a
     * SharedPreferences file to another SharedPreferences file, as Strings.
     */
    private static void copyPreferences(SharedPreferences oldPrefs,
                                        SharedPreferences newPrefs) {
        Map<String, ?> entries = oldPrefs.getAll();
        for (Map.Entry<String, ?> entry : entries.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue().toString();
            newPrefs.edit().putString(key, value).apply();
        }
    }

    /**
     * Part of the AOSP upgrade path, copies all of the key and values
     * in the old camera SharedPreferences files to new files.
     */
    private static void upgradeCameraSettingsFiles(SettingsManager settingsManager,
                                                   Context context) {
        String[] cameraIds =
            context.getResources().getStringArray(R.array.camera_id_entryvalues);

        for (int i = 0; i < cameraIds.length; i++) {
            SharedPreferences oldCameraPreferences =
                settingsManager.openPreferences(
                    OLD_CAMERA_PREFERENCES_PREFIX + cameraIds[i]);
            SharedPreferences newCameraPreferences =
                settingsManager.openPreferences(CameraActivity.CAMERA_SCOPE_PREFIX
                                                + cameraIds[i]);

            copyPreferences(oldCameraPreferences, newCameraPreferences);
        }
    }

    private static void upgradeModuleSettingsFiles(SettingsManager settingsManager,
                                                   Context context, AppController app) {
        int[] moduleIds = context.getResources().getIntArray(R.array.camera_modes);

        for (int i = 0; i < moduleIds.length; i++) {
            String moduleId = Integer.toString(moduleIds[i]);
            SharedPreferences oldModulePreferences =
                settingsManager.openPreferences(
                    OLD_MODULE_PREFERENCES_PREFIX + moduleId);

            ModuleManagerImpl.ModuleAgent agent =
                app.getModuleManager().getModuleAgent(moduleIds[i]);
            if (agent == null) {
                continue;
            }
            ModuleController module = agent.createModule(app);
            SharedPreferences newModulePreferences =
                settingsManager.openPreferences(CameraActivity.MODULE_SCOPE_PREFIX
                                                + module.getModuleStringIdentifier());

            copyPreferences(oldModulePreferences, newModulePreferences);
        }
    }

    /**
     * The R.integer.camera_mode_* indices were cleaned up, resulting in removals and renaming
     * of certain values. In particular camera_mode_gcam is now 5, not 6. We modify any
     * persisted user settings that may refer to the old value.
     */
    private static void upgradeSelectedModeIndex(SettingsManager settingsManager, Context context) {
        int oldGcamIndex = 6; // from hardcoded previous mode index resource
        int gcamIndex = context.getResources().getInteger(R.integer.camera_mode_gcam);

        int lastUsedCameraIndex = settingsManager.getInteger(SettingsManager.SCOPE_GLOBAL,
                Keys.KEY_CAMERA_MODULE_LAST_USED);
        if (lastUsedCameraIndex == oldGcamIndex) {
            settingsManager.set(SettingsManager.SCOPE_GLOBAL, Keys.KEY_CAMERA_MODULE_LAST_USED,
                    gcamIndex);
        }

        int startupModuleIndex = settingsManager.getInteger(SettingsManager.SCOPE_GLOBAL,
                Keys.KEY_STARTUP_MODULE_INDEX);
        if (startupModuleIndex == oldGcamIndex) {
            settingsManager.set(SettingsManager.SCOPE_GLOBAL, Keys.KEY_STARTUP_MODULE_INDEX,
                    gcamIndex);
        }
    }
}
