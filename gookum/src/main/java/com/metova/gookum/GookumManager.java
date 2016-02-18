package com.metova.gookum;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.gcm.GoogleCloudMessaging;

import com.metova.gookum.service.GookumRegistrationIntentService;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;

public abstract class GookumManager {

    private static final String TAG = GookumManager.class.getSimpleName();

    public static final int APP_VERSION_NOT_SAVED = -1;

    public static final int GOOKUM_PLAY_SERVICES_RESOLUTION_REQUEST_CODE_DEFAULT = 1900;

    protected static final String GOOKUM_SHARED_PREFERENCES_NAME = "GOOKUM_SHARED_PREFERENCES";

    private static final String PREFERENCE_GCM_REGISTRATION_ID = "GCM_REGISTRATION_ID";
    private static final String PREFERENCE_SAVED_APP_VERSION = "SAVED_APP_VERSION";

    private GoogleCloudMessaging mGcm;
    private SharedPreferences mSharedPreferences;

    /**
     * Allows the user to set the GoogleCloudMessaging instance used for registration and un-registration (rather than
     * using the default), which is useful primarily for testing purposes.
     * @param gcmInstance The GoogleCloudMessaging instance used to register and unregister the app. Can be
     *        GoogleCloudMessaging.getInstance() or a mocked instance.
     */
    public void setGcmInstance(GoogleCloudMessaging gcmInstance) {
        Log.v(TAG, "setGcmInstance()");
        mGcm = gcmInstance;
    }

    /**
     * @return True if registration ID is stored and the current app version is registered, otherwise false
     */
    @Deprecated
    public boolean isRegistrationValid() {
        Log.v(TAG, "isRegistrationValid()");
        return !TextUtils.isEmpty(getGcmRegistrationId()) && (getSavedAppVersion() == getCurrentAppVersion());
    }

    /**
     * @return The current version number of the app
     */
    protected int getCurrentAppVersion() {
        Log.v(TAG, "getCurrentAppVersion()");
        try {
            Context context = getContext();
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return packageInfo.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException("Could not get package name: " + e);
        }
    }

    /**
     * @return The version number of the app last time it registered to GCM
     */
    @Deprecated
    protected int getSavedAppVersion() {
        return getGookumSharedPreferences().getInt(PREFERENCE_SAVED_APP_VERSION, APP_VERSION_NOT_SAVED);
    }

    /**
     * @param appVersion The current app version, to save for checking in the future
     */
    @Deprecated
    protected void setSavedAppVersion(int appVersion) {
        getGookumSharedPreferences().edit()
                .putInt(PREFERENCE_SAVED_APP_VERSION, appVersion)
                .apply();
    }

    /**
     * @return The app instance's stored GCM registration ID
     */
    @Deprecated
    protected String getGcmRegistrationId() {
        return getGookumSharedPreferences().getString(PREFERENCE_GCM_REGISTRATION_ID, null);
    }

    /**
     * @param gcmRegistrationId The app instance's GCM registration ID, to store
     */
    @Deprecated
    protected void setGcmRegistrationId(String gcmRegistrationId) {
        getGookumSharedPreferences().edit()
                .putString(PREFERENCE_GCM_REGISTRATION_ID, gcmRegistrationId)
                .apply();
    }

    /**
     * Get the request code used to call startActivityForResult() upon a Play Services error.
     * @return {@link #GOOKUM_PLAY_SERVICES_RESOLUTION_REQUEST_CODE_DEFAULT}
     */
    public int getPlayServicesResolutionRequestCode() {
        return GOOKUM_PLAY_SERVICES_RESOLUTION_REQUEST_CODE_DEFAULT;
    }

    /**
     * Registers the app with GCM.
     */
    public void registerGcm() {
        if (!isGcmEnabled()) {
            Log.v(TAG, "registerGcm(): GCM is not enabled; returning without registering");
            return;
        }

        Context context = getContext();
        context.startService(new Intent(context, getRegistrationIntentServiceClass()));
    }

    /**
     * Registers the current installation of the app with GCM.
     * @param callback The RegisterGcmCallback to call upon completion of attempted GCM registration
     */
    @Deprecated
    public void registerGcm(@Nullable final RegistrationCallback callback) {
        Log.v(TAG, "registerGcm(RegistrationCallback)");
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... params) {
                if (!isGcmEnabled()) {
                    cancel(true);
                    return null;
                }

                Context context = getContext();
                context.startService(new Intent(context, getRegistrationIntentServiceClass()));

                String registrationId;
                try {
                    if (mGcm == null) {
                        mGcm = GoogleCloudMessaging.getInstance(getContext());
                    }

                    registrationId = mGcm.register(getGcmSenderId());
                    setGcmRegistrationId(registrationId);
                    setSavedAppVersion(getCurrentAppVersion());
                } catch (IOException e) {
                    Log.e(TAG, "GCM registration error: " + e.getMessage());
                    return null;
                }

                return registrationId;
            }

            @Override
            protected void onPostExecute(String registrationId) {
                if (TextUtils.isEmpty(registrationId)) {
                    Log.e(TAG, "registrationId came back empty; GCM registration failed");
                    if (callback != null) {
                        callback.onError();
                    }
                } else {
                    Log.i(TAG, "Successfully registered to GCM with registrationId = " + registrationId);
                    if (callback != null) {
                        callback.onRegistered(registrationId);
                    }
                }
            }

            @Override
            protected void onCancelled(String registrationId) {
                Log.w(TAG, "Attempted GCM registration when GCM is not enabled");
                if (callback != null) {
                    callback.onGcmDisabled();
                }
            }
        }.execute();
    }

    /**
     * Unregisters the current installation of the app from GCM.
     * @param callback The UnregisterGcmCallback to call upon completion of the attempted GCM un-registration
     */
    @Deprecated
    public void unregisterGcm(@Nullable final UnregistrationCallback callback) {
        Log.v(TAG, "unregisterGcm()");
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... params) {
                try {
                    if (mGcm == null) {
                        mGcm = GoogleCloudMessaging.getInstance(getContext());
                    }
                    mGcm.unregister();
                    setGcmRegistrationId("");
                    setSavedAppVersion(APP_VERSION_NOT_SAVED);
                    return true;
                } catch (IOException e) {
                    Log.e(TAG, "GCM un-registration error: " + e.getMessage());
                    return false;
                }
            }

            @Override
            protected void onPostExecute(Boolean didSucceed) {
                if (didSucceed) {
                    Log.i(TAG, "Unregistered from GCM successfully");
                    if (callback != null) {
                        callback.onUnregistered();
                    }
                } else {
                    Log.e(TAG, "Failed to unregister from GCM");
                    if (callback != null) {
                        callback.onError();
                    }
                }
            }
        }.execute();
    }

    /**
     * @return The result code describing whether Play Services are enabled
     */
    public int checkPlayServices() {
        Log.v(TAG, "checkPlayServices()");
        GoogleApiAvailability apiAvailability = GoogleApiAvailability.getInstance();
        return apiAvailability.isGooglePlayServicesAvailable(getContext());
    }

    /**
     * @param playServicesResultCode The result code describing whether Play Services are enabled
     * @param activity The Activity over which to show an error dialog
     *
     * @return False if Play Services are not supported on this device, otherwise true.
     */
    public boolean notifyPlayServicesAvailability(int playServicesResultCode, Activity activity) {
        Log.v(TAG, "notifyPlayServicesAvailability()");
        if (playServicesResultCode == ConnectionResult.SUCCESS) {
            Toast.makeText(getContext(), "Play Services are enabled.", Toast.LENGTH_SHORT)
                    .show();
            return true;
        } else {
            GoogleApiAvailability apiAvailability = GoogleApiAvailability.getInstance();
            if (apiAvailability.isUserResolvableError(playServicesResultCode)) {
                apiAvailability.getErrorDialog(activity, playServicesResultCode, getPlayServicesResolutionRequestCode())
                        .show();
                return true;
            } else {
                String notSupported = "This device does not support Play Services";
                Log.e(TAG, notSupported + ": error code " + playServicesResultCode);
                Toast.makeText(getContext(), notSupported + ".", Toast.LENGTH_LONG)
                        .show();
                return false;
            }
        }
    }

    /**
     * @deprecated Because this is an awkward method that does two different things.
     *
     * @param activity Activity on which to possibly display an error dialog
     * @return True if the device supports Play Services, otherwise false
     */
    @Deprecated
    public boolean checkIfGooglePlayServicesAreEnabled(Activity activity) {
        Log.v(TAG, "checkIfGooglePlayServicesAreEnabled()");
        int resultCode = GooglePlayServicesUtil.isGooglePlayServicesAvailable(getContext());
        if (resultCode != ConnectionResult.SUCCESS) {
            if (GooglePlayServicesUtil.isUserRecoverableError(resultCode)) {
                GooglePlayServicesUtil.getErrorDialog(resultCode, activity, getPlayServicesResolutionRequestCode()).show();
            } else {
                Log.i(TAG, "This device does not support Play Services.");
            }

            return false;
        }

        return true;
    }

    /**
     * @return The SharedPreferences file used by GookumManager to store the GcmRegistrationId.
     */
    public SharedPreferences getGookumSharedPreferences() {
        if (mSharedPreferences == null) {
            mSharedPreferences = getGookumSharedPreferences(getContext());
        }

        return mSharedPreferences;
    }

    public static SharedPreferences getGookumSharedPreferences(Context context) {
        return context.getSharedPreferences(GOOKUM_SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    //region Abstract methods
    /**
     * If GCM is not enabled at all, this class won't try to register the app.
     * @return True if push notifications are enabled in general; false otherwise
     */
    protected abstract boolean isGcmEnabled();

    /**
     * @return The implementation of RegistrationIntentService being used in your app.
     */
    protected abstract Class<? extends GookumRegistrationIntentService> getRegistrationIntentServiceClass();

    /**
     * @return The "Project Number" of your API project on the Google Developers Console
     */
    protected abstract String getGcmSenderId();

    /**
     * @return The Context of the app
     */
    protected abstract Context getContext();
    //endregion

    @Deprecated
    public interface RegistrationCallback {

        /**
         * Called when GCM registration succeeds.
         *
         * @param registrationId The app instance's registration ID, returned by GCM
         */
        void onRegistered(String registrationId);

        /**
         * Called when GCM registration fails.
         */
        void onError();

        /**
         * Called when, upon attempting to register the app instance for GCM, {@link #isGcmEnabled()} returns false.
         */
        void onGcmDisabled();
    }

    @Deprecated
    public interface UnregistrationCallback {

        /**
         * Called when GCM un-registration succeeds.
         */
        void onUnregistered();

        /**
         * Called when GCM un-registration fails.
         */
        void onError();
    }
}
