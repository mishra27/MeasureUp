/*
 * Copyright 2017 Google Inc. All Rights Reserved.
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

package com.example.aksha.measureup;

import android.opengl.EGL14;
import android.opengl.EGLDisplay;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Environment;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.example.common.helpers.CameraPermissionHelper;
import com.example.common.helpers.DisplayRotationHelper;
import com.example.common.helpers.FullScreenHelper;
import com.example.common.helpers.SnackbarHelper;
import com.example.common.helpers.TapHelper;
import com.example.common.rendering.BackgroundRenderer;
import com.example.common.rendering.PlaneRenderer;
import com.example.common.rendering.PointCloudRenderer;
import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Frame;
import com.google.ar.core.Plane;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.opengles.GL10;

/**
 * This is a simple example that shows how to create an augmented reality (AR) application using the
 * ARCore API. The application will display any detected planes and will allow the user to tap on a
 * plane to place a 3d model of the Android robot.
 */
public class MainActivity extends AppCompatActivity implements GLSurfaceView.Renderer {
    private static final String TAG = MainActivity.class.getSimpleName();


        // Rendering. The Renderers are created here, and initialized when the GL surface is created.
        private GLSurfaceView surfaceView;
        private VideoRecorder mRecorder;
        private android.opengl.EGLConfig mAndroidEGLConfig;

        private boolean installRequested;

        private Session session;
        private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
        private DisplayRotationHelper displayRotationHelper;
        private TapHelper tapHelper;

        private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();

        private final PlaneRenderer planeRenderer = new PlaneRenderer();
        private final PointCloudRenderer pointCloudRenderer = new PointCloudRenderer();

        // Temporary matrix allocated here to reduce number of allocations for each frame.
        private final float[] anchorMatrix = new float[16];
        private static final float[] DEFAULT_COLOR = new float[] {0f, 0f, 0f, 0f};

        // Anchors created from taps used for object placing with a given color.
        private static class ColoredAnchor {
            public final Anchor anchor;
            public final float[] color;

            public ColoredAnchor(Anchor a, float[] color4f) {
                this.anchor = a;
                this.color = color4f;
            }
        }

        private final ArrayList<ColoredAnchor> anchors = new ArrayList<>();

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            setContentView(R.layout.activity_main);
            surfaceView = findViewById(R.id.surfaceview);
            displayRotationHelper = new DisplayRotationHelper(/*context=*/ this);

            // Set up tap listener.
            tapHelper = new TapHelper(/*context=*/ this);
            surfaceView.setOnTouchListener(tapHelper);

            // Set up renderer.
            surfaceView.setPreserveEGLContextOnPause(true);
            surfaceView.setEGLContextClientVersion(2);
            surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending.
            surfaceView.setRenderer(this);
            surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

            installRequested = false;
        }

        @Override
        protected void onResume() {
            super.onResume();

            if (session == null) {
                Exception exception = null;
                String message = null;
                try {
                    switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                        case INSTALL_REQUESTED:
                            installRequested = true;
                            return;
                        case INSTALLED:
                            break;
                    }

                    // ARCore requires camera permissions to operate. If we did not yet obtain runtime
                    // permission on Android M and above, now is a good time to ask the user for it.
                    if (!CameraPermissionHelper.hasCameraPermission(this)) {
                        CameraPermissionHelper.requestCameraPermission(this);
                        return;
                    }

                    // Create the session.
                    session = new Session(/* context= */ this);

                } catch (UnavailableArcoreNotInstalledException
                        | UnavailableUserDeclinedInstallationException e) {
                    message = "Please install ARCore";
                    exception = e;
                } catch (UnavailableApkTooOldException e) {
                    message = "Please update ARCore";
                    exception = e;
                } catch (UnavailableSdkTooOldException e) {
                    message = "Please update this app";
                    exception = e;
                } catch (UnavailableDeviceNotCompatibleException e) {
                    message = "This device does not support AR";
                    exception = e;
                } catch (Exception e) {
                    message = "Failed to create AR session";
                    exception = e;
                }

                if (message != null) {
                    messageSnackbarHelper.showError(this, message);
                    Log.e(TAG, "Exception creating session", exception);
                    return;
                }
            }

            // Note that order matters - see the note in onPause(), the reverse applies here.
            try {
                session.resume();
            } catch (CameraNotAvailableException e) {
                // In some cases (such as another camera app launching) the camera may be given to
                // a different app instead. Handle this properly by showing a message and recreate the
                // session at the next iteration.
                messageSnackbarHelper.showError(this, "Camera not available. Please restart the app.");
                session = null;
                return;
            }

            surfaceView.onResume();
            displayRotationHelper.onResume();

            messageSnackbarHelper.showMessage(this, "Searching for surfaces...");
        }

        @Override
        public void onPause() {
            super.onPause();
            if (session != null) {
                // Note that the order matters - GLSurfaceView is paused first so that it does not try
                // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
                // still call session.update() and get a SessionPausedException.
                displayRotationHelper.onPause();
                surfaceView.onPause();
                session.pause();
            }
        }

        @Override
        public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
            if (!CameraPermissionHelper.hasCameraPermission(this)) {
                Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
                        .show();
                if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                    // Permission denied with checking "Do not ask again".
                    CameraPermissionHelper.launchPermissionSettings(this);
                }
                finish();
            }
        }

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            super.onWindowFocusChanged(hasFocus);
            FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

            EGL10 egl10 =  (EGL10)EGLContext.getEGL();
            javax.microedition.khronos.egl.EGLDisplay display = egl10.eglGetCurrentDisplay();
            int v[] = new int[2];
            egl10.eglGetConfigAttrib(display,config, EGL10.EGL_CONFIG_ID, v);

            EGLDisplay androidDisplay = EGL14.eglGetCurrentDisplay();
            int attribs[] = {EGL14.EGL_CONFIG_ID, v[0], EGL14.EGL_NONE};
            android.opengl.EGLConfig myConfig[] = new android.opengl.EGLConfig[1];
            EGL14.eglChooseConfig(androidDisplay, attribs, 0, myConfig, 0, 1, v, 1);
            this.mAndroidEGLConfig = myConfig[0];

            // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
            try {
                // Create the texture and pass it to ARCore session to be filled during update().
                backgroundRenderer.createOnGlThread(/*context=*/ this);
                planeRenderer.createOnGlThread(/*context=*/ this, "models/trigrid.png");
                pointCloudRenderer.createOnGlThread(/*context=*/ this);


            } catch (IOException e) {
                Log.e(TAG, "Failed to read an asset file", e);
            }
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            displayRotationHelper.onSurfaceChanged(width, height);
            GLES20.glViewport(0, 0, width, height);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            draw(gl);

            if (session == null) {
                return;
            }
            // Notify ARCore session that the view size changed so that
            // the perspective matrix and
            // the video background can be properly adjusted.
            displayRotationHelper.updateSessionIfNeeded(session);

            try {
                // Obtain the current frame from ARSession. When the
                //configuration is set to
                // UpdateMode.BLOCKING (it is by default), this will
                // throttle the rendering to the camera framerate.
                Frame frame = session.update();
                Camera camera = frame.getCamera();

                // Handle taps. Handling only one tap per frame, as taps are
                // usually low frequency compared to frame rate.


                // Get projection matrix.
                float[] projmtx = new float[16];
                camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f);

                // Get camera matrix and draw.
                float[] viewmtx = new float[16];
                camera.getViewMatrix(viewmtx, 0);

                // Compute lighting from average intensity of the image.
                final float lightIntensity = frame.getLightEstimate().getPixelIntensity();

                // Visualize tracked points.
                PointCloud pointCloud = frame.acquirePointCloud();
                pointCloudRenderer.update(pointCloud);


                draw(frame,camera.getTrackingState() == TrackingState.PAUSED,
                        viewmtx, projmtx, camera.getDisplayOrientedPose(),lightIntensity);

                if (mRecorder!= null && mRecorder.isRecording()) {
                    VideoRecorder.CaptureContext ctx = mRecorder.startCapture();
                    if (ctx != null) {
                        // draw again
                        draw(frame, camera.getTrackingState() == TrackingState.PAUSED,
                                viewmtx, projmtx, camera.getDisplayOrientedPose(), lightIntensity);

                        // restore the context
                        mRecorder.stopCapture(ctx, frame.getTimestamp());
                    }
                }



                // Application is responsible for releasing the point cloud resources after
                // using it.
                pointCloud.release();


            } catch (Throwable t) {
                // Avoid crashing the application due to unhandled exceptions.
                Log.e(TAG, "Exception on the OpenGL thread", t);
            }
        }


        private void draw(Frame frame, boolean paused,
                          float[] viewMatrix, float[] projectionMatrix,
                          Pose displayOrientedPose, float lightIntensity) {

            // Clear screen to notify driver it should not load
            // any pixels from previous frame.
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

            // Draw background.
            backgroundRenderer.draw(frame);

            // If not tracking, don't draw 3d objects.
            if (paused) {
                return;
            }

            pointCloudRenderer.draw(viewMatrix, projectionMatrix);




        }

        private void draw(GL10 gl) {
            // Clear screen to notify driver it should not load any pixels from previous frame.

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

            if (session == null) {
                return;
            }
            // Notify ARCore session that the view size changed so that the perspective matrix and
            // the video background can be properly adjusted.
            displayRotationHelper.updateSessionIfNeeded(session);

            try {
                session.setCameraTextureName(backgroundRenderer.getTextureId());

                // Obtain the current frame from ARSession. When the configuration is set to
                // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
                // camera framerate.
                Frame frame = session.update();
                Camera camera = frame.getCamera();

                // Handle one tap per frame.
                handleTap(frame, camera);

                // Draw background.
                backgroundRenderer.draw(frame);

                // If not tracking, don't draw 3d objects.
                if (camera.getTrackingState() == TrackingState.PAUSED) {
                    return;
                }

                // Get projection matrix.
                float[] projmtx = new float[16];
                camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f);

                // Get camera matrix and draw.
                float[] viewmtx = new float[16];
                camera.getViewMatrix(viewmtx, 0);

                // Compute lighting from average intensity of the image.
                // The first three components are color scaling factors.
                // The last one is the average pixel intensity in gamma space.
                final float[] colorCorrectionRgba = new float[4];
                frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0);

                // Visualize tracked points.
                PointCloud pointCloud = frame.acquirePointCloud();
                pointCloudRenderer.update(pointCloud);
                pointCloudRenderer.draw(viewmtx, projmtx);

                // Application is responsible for releasing the point cloud resources after
                // using it.
                pointCloud.release();

                // Check if we detected at least one plane. If so, hide the loading message.
                if (messageSnackbarHelper.isShowing()) {
                    for (Plane plane : session.getAllTrackables(Plane.class)) {
                        if (plane.getTrackingState() == TrackingState.TRACKING) {
                            messageSnackbarHelper.hide(this);
                            break;
                        }
                    }
                }

                // Visualize planes.

            } catch (Throwable t) {
                // Avoid crashing the application due to unhandled exceptions.
                Log.e(TAG, "Exception on the OpenGL thread", t);
            }
        }

        // Handle only one tap per frame, as taps are usually low frequency compared to frame rate.
        private void handleTap(Frame frame, Camera camera) {

        }

        public void clickToggleRecording(View view) {
            Log.d(TAG, "clickToggleRecording");
            if (mRecorder == null) {
                File outputFile = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES) + "/MeasureUp",
                        "Object-" + Long.toHexString(System.currentTimeMillis()) + ".mp4");
                File dir = outputFile.getParentFile();
                if (!dir.exists()) {
                    dir.mkdirs();
                }

                try {
                    mRecorder = new VideoRecorder(surfaceView.getWidth(),
                            surfaceView.getHeight(),
                            VideoRecorder.DEFAULT_BITRATE, outputFile, this);
                    mRecorder.setEglConfig(mAndroidEGLConfig);
                } catch (IOException e) {
                    Log.e(TAG,"Exception starting recording", e);
                }
            }
            mRecorder.toggleRecording();
            updateControls();
        }

        private void updateControls() {
            Button toggleRelease = findViewById(R.id.fboRecord_button);
            int id = (mRecorder != null && mRecorder.isRecording()) ?
                    R.string.toggleRecordingOff : R.string.toggleRecordingOn;
            toggleRelease.setText(id);

            TextView tv =  findViewById(R.id.nowRecording_text);
            if (id == R.string.toggleRecordingOff) {
                tv.setText("Recording");
            } else {
                tv.setText(" Click ");
            }
        }
    }






