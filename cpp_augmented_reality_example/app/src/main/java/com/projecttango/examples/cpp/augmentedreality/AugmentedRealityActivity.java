/*
 * Copyright 2014 Google Inc. All Rights Reserved.
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

package com.projecttango.examples.cpp.augmentedreality;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Point;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The main activity of the application which shows debug information and a
 * glSurfaceView that renders graphic content.
 */
public class AugmentedRealityActivity extends Activity implements
    View.OnClickListener {
  // The minimum Tango Core version required from this application.
  private static final int MIN_TANGO_CORE_VERSION = 6804;

  // Tag for debug logging.
  private static final String TAG = AugmentedRealityActivity.class.getSimpleName();

  // The interval at which we'll update our UI debug text in milliseconds.
  // This is the rate at which we query our native wrapper around the tango
  // service for pose and event information.
  private static final int UPDATE_UI_INTERVAL_MS = 100;

  // Debug information text.
  // Current frame's pose information.
  private TextView mPoseData;
  // Tango Core version.
  private TextView mVersion;
  // Application version.
  private TextView mAppVersion;
  // Latest Tango Event received.
  private TextView mEvent;

  // Button for manually resetting motion tracking. Resetting motion tracking
  // will restart the tracking pipeline, which also means the user will have to
  // wait for re-initialization of the motion tracking system.
  private Button mMotionReset;

  // GLSurfaceView and its renderer, all of the graphic content is rendered
  // through OpenGL ES 2.0 in the native code.
  private AugmentedRealityRenderer mRenderer;
  private GLSurfaceView mGLView;

  // A flag to check if the Tango Service is connected. This flag avoids the
  // program attempting to disconnect from the service while it is not
  // connected.This is especially important in the onPause() callback for the
  // activity class.
  private boolean mIsConnectedService = false;

  // Screen size for normalizing the touch input for orbiting the render camera.
  private Point mScreenSize = new Point();

  // Handles the debug text UI update loop.
  private Handler mHandler = new Handler();

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                         WindowManager.LayoutParams.FLAG_FULLSCREEN);

    // Querying screen size, used for computing the normalized touch point.
    Display display = getWindowManager().getDefaultDisplay();
    display.getSize(mScreenSize);

    // Setting content view of this activity and getting the mIsAutoRecovery
    // flag from StartActivity.
    setContentView(R.layout.activity_augmented_reality);

    // Text views for displaying translation and rotation data
    mPoseData = (TextView) findViewById(R.id.pose_data_textview);

    // Text views for displaying most recent Tango Event
    mEvent = (TextView) findViewById(R.id.tango_event_textview);

    // Text views for Tango library versions
    mVersion = (TextView) findViewById(R.id.version_textview);

    // Text views for application versions.
    mAppVersion = (TextView) findViewById(R.id.appversion);
    PackageInfo pInfo;
    try {
      pInfo = this.getPackageManager().getPackageInfo(this.getPackageName(), 0);
      mAppVersion.setText(pInfo.versionName);
    } catch (NameNotFoundException e) {
      e.printStackTrace();
    }

    // Buttons for selecting camera view and Set up button click listeners
    findViewById(R.id.first_person_button).setOnClickListener(this);
    findViewById(R.id.third_person_button).setOnClickListener(this);
    findViewById(R.id.top_down_button).setOnClickListener(this);

    // Button to reset motion tracking
    mMotionReset = (Button) findViewById(R.id.resetmotion);

    // OpenGL view where all of the graphics are drawn
    mGLView = (GLSurfaceView) findViewById(R.id.gl_surface_view);
    
    // Configure OpenGL renderer
    mGLView.setEGLContextClientVersion(2);

    // Set up button click listeners
    mMotionReset.setOnClickListener(this);

    // Configure OpenGL renderer. The RENDERMODE_WHEN_DIRTY is set explicitly
    // for reducing the CPU load. The request render function call is triggered
    // by the onTextureAvailable callback from the Tango Service in the native
    // code.
    mRenderer = new AugmentedRealityRenderer();
    mGLView.setRenderer(mRenderer);
    mGLView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

    // Check that the installed version of the Tango Core is up to date.
    if (!TangoJNINative.initialize(this, MIN_TANGO_CORE_VERSION)) {
      Toast.makeText(this, "Tango Core out of date, please update in Play Store", 
                     Toast.LENGTH_LONG).show();
      finish();
      return;
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    mGLView.onResume();
    
    // Setup the configuration for the TangoService.
    TangoJNINative.setupConfig();

    // Connect the onPoseAvailable callback.
    TangoJNINative.connectCallbacks();

    // Connect to Tango Service (returns true on success).
    // Starts Motion Tracking and Area Learning.
    if (TangoJNINative.connect()) {
      mIsConnectedService = true;
      mVersion.setText(TangoJNINative.getVersionNumber());
    } else {
      // End the activity and let the user know something went wrong.
      Toast.makeText(this, "Connect Tango Service Error", Toast.LENGTH_LONG).show();
      finish();
    }

    // Start the debug text UI update loop.
    mHandler.post(mUpdateUiLoopRunnable);
  }

  @Override
  protected void onPause() {
    super.onPause();
    mGLView.onPause();
    TangoJNINative.deleteResources();

    // Disconnect from Tango Service, release all the resources that the app is
    // holding from Tango Service.
    if (mIsConnectedService) {
      TangoJNINative.disconnect();
      mIsConnectedService = false;
    }

    // Stop the debug text UI update loop.
    mHandler.removeCallbacksAndMessages(null);
  }

  @Override
  protected void onDestroy() {
      super.onDestroy();
      TangoJNINative.destroyActivity();
  }

  @Override
  public void onClick(View v) {
    // Handle button clicks.
    switch (v.getId()) {
      case R.id.first_person_button:
        TangoJNINative.setCamera(0);
        break;
      case R.id.top_down_button:
        TangoJNINative.setCamera(2);
        break;
      case R.id.third_person_button:
        TangoJNINative.setCamera(1);
        break;
      case R.id.resetmotion:
        TangoJNINative.resetMotionTracking();
        break;
      default:
        Log.w(TAG, "Unknown button click");
        return;
    }
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    // Pass the touch event to the native layer for camera control.
    // Single touch to rotate the camera around the device.
    // Two fingers to zoom in and out.
    int pointCount = event.getPointerCount();
    if (pointCount == 1) {
      float normalizedX = event.getX(0) / mScreenSize.x;
      float normalizedY = event.getY(0) / mScreenSize.y;
      TangoJNINative.onTouchEvent(1,
          event.getActionMasked(), normalizedX, normalizedY, 0.0f, 0.0f);
    }
    if (pointCount == 2) {
      if (event.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {
        int index = event.getActionIndex() == 0 ? 1 : 0;
        float normalizedX = event.getX(index) / mScreenSize.x;
        float normalizedY = event.getY(index) / mScreenSize.y;
        TangoJNINative.onTouchEvent(1,
          MotionEvent.ACTION_DOWN, normalizedX, normalizedY, 0.0f, 0.0f);
      } else {
        float normalizedX0 = event.getX(0) / mScreenSize.x;
        float normalizedY0 = event.getY(0) / mScreenSize.y;
        float normalizedX1 = event.getX(1) / mScreenSize.x;
        float normalizedY1 = event.getY(1) / mScreenSize.y;
        TangoJNINative.onTouchEvent(2, event.getActionMasked(),
            normalizedX0, normalizedY0, normalizedX1, normalizedY1);
      }
    }
    return true;
  }

  // Request render on the glSurfaceView. This function is called from the 
  // native code, and it is triggered from the onTextureAvailable callback from
  // the Tango Service.
  public void requestRender() {
    mGLView.requestRender();
  }

  // Debug text UI update loop, updating at 10Hz.
  private Runnable mUpdateUiLoopRunnable = new Runnable() {
    public void run() {
      updateUi();
      mHandler.postDelayed(this, UPDATE_UI_INTERVAL_MS);
    }
  };

  // Update the debug text UI.
  private void updateUi() {
    try {
      mEvent.setText(TangoJNINative.getEventString());
      mPoseData.setText(TangoJNINative.getPoseString());
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
