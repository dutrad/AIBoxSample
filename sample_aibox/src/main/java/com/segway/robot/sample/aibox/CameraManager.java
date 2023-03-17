package com.segway.robot.sample.aibox;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class CameraManager {
    private static final String TAG = CameraManager.class.getSimpleName();
    private static final int MAX_PREVIEW_WIDTH = 1920;
    private static final int MAX_PREVIEW_HEIGHT = 1080;
    private CameraDevice mCameraDevice;
    private Activity mActivity;
    private ImageReader mImageReader;
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);
    private String mCameraId;
    private CameraCaptureSession mCaptureSession;
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private CaptureRequest mPreviewRequest;
    private DataCallback mDataCallback;
    private int mWidth;
    private int mHeight;

    public CameraManager(Activity activity) {
        mActivity = activity;
        startBackgroundThread();
    }

    public void setDataCallback(DataCallback dataCallback) {
        mDataCallback = dataCallback;
    }

    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    @SuppressLint("MissingPermission")
    public void openCamera(int width, int height) {
        mCameraId = "0";
        mWidth = width;
        mHeight = height;
        mImageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2);
        mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler);
        android.hardware.camera2.CameraManager manager = (android.hardware.camera2.CameraManager) mActivity.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            manager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);
        } catch (Exception e) {
            Log.e(TAG, "openCamera: ", e);
        }
    }

    public void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCaptureSession) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mImageReader) {
                mImageReader.close();
                mImageReader = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(CameraDevice cameraDevice) {
            // This method is called when the camera is opened.  We start camera preview here.
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(CameraDevice cameraDevice, int error) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

    };

    private void createCameraPreviewSession() {
        try {
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(mImageReader.getSurface());
            // Here, we create a CameraCaptureSession for camera preview.
            mCameraDevice.createCaptureSession(Arrays.asList(mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                            // The camera is already closed
                            if (null == mCameraDevice) {
                                return;
                            }

                            // When the session is ready, we start displaying the preview.
                            mCaptureSession = cameraCaptureSession;
                            try {
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);


                                mPreviewRequest = mPreviewRequestBuilder.build();
                                mCaptureSession.setRepeatingRequest(mPreviewRequest,
                                        mCaptureCallback, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(
                                CameraCaptureSession cameraCaptureSession) {
                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private CameraCaptureSession.CaptureCallback mCaptureCallback
            = new CameraCaptureSession.CaptureCallback() {

        private void process(CaptureResult result) {

        }

        @Override
        public void onCaptureProgressed(CameraCaptureSession session,
                                        CaptureRequest request,
                                        CaptureResult partialResult) {
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession session,
                                       CaptureRequest request,
                                       TotalCaptureResult result) {
        }

    };

    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = reader -> {
        Image image = reader.acquireLatestImage();
        if (image != null) {
            Log.d(TAG, "ts: " + image.getTimestamp());
            ByteBuffer ybuffer = image.getPlanes()[0].getBuffer();
            ByteBuffer uvBuffer = image.getPlanes()[1].getBuffer();
            ByteBuffer byteBuffer = ByteBuffer.allocate(mWidth * mHeight * 3 / 2);
            byteBuffer.put(ybuffer);
            byteBuffer.put(uvBuffer);
            if(mDataCallback != null) {
                mDataCallback.onCallback(byteBuffer, image.getWidth(), image.getHeight());
            }
            image.close();
        }
    };

    interface DataCallback {
        void onCallback(ByteBuffer buffer, int width, int height);
    }
}
