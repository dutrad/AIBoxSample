package com.segway.robot.sample.aibox;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements CameraManager.DataCallback {

    private static final String TAG = MainActivity.class.getSimpleName();
    private static final String LOCAL_IMAGE_PATH = "sdcard/apple.jpeg";
    private static final int REQUEST_CODE = 1;
    private static String[] PERMISSIONS_STORAGE = new String[]{
            Manifest.permission.CAMERA,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.RECORD_AUDIO
    };
    private static final int BITMAP_SCALE = 4;
    private VisionImageView mImageView;
    private volatile boolean mIsBind;
    private volatile boolean mIsDetecting;
    private volatile boolean mIsImageStarted;
    private volatile boolean mIsCameraStarted;
    private Bitmap mBitmap;
    private Thread mVisionWorkThread;
    private Thread mImageWorkThread;
    private final Object mBitmapLock = new Object();
    private Button mBtnOpenImage;
    private Button mBtnCloseImage;
    private Button mBtnOpenCamera;
    private Button mBtnCloseCamera;
    private Button mBtnStart;
    private Button mBtnStop;
    private ByteBuffer mData;
    private DetectedResult[] mDetectedResults;
    private List<RectF> mRectList = new ArrayList<>();
    private int mImageViewWidth;
    private int mImageViewHeight;
    private CameraManager mCameraManager;

    static {
        System.loadLibrary("vision_aibox");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mImageView = findViewById(R.id.image);
        mBtnOpenImage = findViewById(R.id.btn_open_image);
        mBtnCloseImage = findViewById(R.id.btn_close_image);
        mBtnOpenCamera = findViewById(R.id.btn_open_camera);
        mBtnCloseCamera = findViewById(R.id.btn_close_camera);
        mBtnStart = findViewById(R.id.btn_start);
        mBtnStop = findViewById(R.id.btn_stop);
        checkPermission();
        resetUI();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeImage();
        closeCamera();
    }

    private void resetUI() {
        mBtnOpenCamera.setEnabled(true);
        mBtnOpenImage.setEnabled(true);
        mBtnStart.setEnabled(false);
        mBtnStop.setEnabled(false);
        mBtnCloseCamera.setEnabled(false);
        mBtnCloseImage.setEnabled(false);
    }

    private void checkPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission
                    .WRITE_EXTERNAL_STORAGE)) {
                Toast.makeText(this, "Please open the relevant permissions, otherwise you can not use this application normally!", Toast.LENGTH_SHORT).show();
            }
            ActivityCompat.requestPermissions(this, PERMISSIONS_STORAGE, REQUEST_CODE);
        }
    }

    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.btn_open_image:
                openImage();
                break;
            case R.id.btn_close_image:
                closeImage();
                break;
            case R.id.btn_open_camera:
                openCamera();
                break;
            case R.id.btn_close_camera:
                closeCamera();
                break;
            case R.id.btn_start:
                startDetect();
                break;
            case R.id.btn_stop:
                stopDetect();
                break;
            default:
                break;
        }
    }

    private synchronized void openImage() {

        if (mIsImageStarted) {
            return;
        }

        mBtnOpenCamera.setEnabled(false);

        mIsImageStarted = true;
        mImageWorkThread = new ImageWorkThread();
        mImageWorkThread.start();

        mBtnStart.setEnabled(true);
        mBtnCloseImage.setEnabled(true);
        mBtnOpenImage.setEnabled(false);

    }

    private synchronized void closeImage() {
        if (mIsDetecting) {
            stopDetect();
        }
        mIsImageStarted = false;
        if (mVisionWorkThread != null) {
            try {
                mImageWorkThread.interrupt();
                mImageWorkThread.join();
            } catch (Exception e) {
                e.printStackTrace();
            }
            mImageWorkThread = null;
        }
        resetUI();
    }

    private synchronized void openCamera() {
        mIsCameraStarted = true;
        mBtnOpenImage.setEnabled(false);
        bindAndStartVision();
    }

    private synchronized void closeCamera() {
        mIsCameraStarted = false;
        if (mIsDetecting) {
            stopDetect();
        }
        unbindAndStopVision();
        if (mVisionWorkThread != null) {
            try {
                mVisionWorkThread.interrupt();
                mVisionWorkThread.join();
            } catch (Exception e) {
                e.printStackTrace();
            }
            mVisionWorkThread = null;
        }
        resetUI();
    }

    private synchronized void startDetect() {
        if (mIsDetecting) {
            return;
        }
        mIsDetecting = true;

        mBtnStop.setEnabled(true);
        mBtnStart.setEnabled(false);
    }


    private synchronized void stopDetect() {
        mIsDetecting = false;

        mBtnStop.setEnabled(false);
        mBtnStart.setEnabled(true);
    }

    private void bindAndStartVision() {
        if (mCameraManager == null) {
            mCameraManager = new CameraManager(this);
            mCameraManager.setDataCallback(this);
            mCameraManager.openCamera(1920, 1080);
        }

    }

    private void unbindAndStopVision() {
        if (mCameraManager != null) {
            mCameraManager.closeCamera();
            mCameraManager = null;
        }
    }

    private void showImage() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                synchronized (mBitmapLock) {
                    mRectList.clear();
                    if (mDetectedResults != null) {
                        for (DetectedResult result : mDetectedResults) {
                            mRectList.add(new RectF(result.x1 / BITMAP_SCALE, result.y1 / BITMAP_SCALE,
                                    result.x2 / BITMAP_SCALE, result.y2 / BITMAP_SCALE));
                        }
                    }
                    if (mBitmap != null) {
                        int width = mBitmap.getWidth() / BITMAP_SCALE;
                        int height = mBitmap.getHeight() / BITMAP_SCALE;
                        if (width != mImageViewWidth || height != mImageViewHeight) {
                            mImageViewWidth = width;
                            mImageViewHeight = height;
                            ViewGroup.LayoutParams layoutParams = mImageView.getLayoutParams();
                            layoutParams.width = mImageViewWidth;
                            layoutParams.height = mImageViewHeight;
                            mImageView.setLayoutParams(layoutParams);

                        }
                    }
                    mImageView.mark(mRectList);
                    mImageView.setImageBitmap(mBitmap);
                }
            }
        });
    }

    @Override
    public void onCallback(ByteBuffer buffer, int width, int height) {
//        synchronized (mBitmapLock) {
//            if (mBitmap == null) {
//                mBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
//            }
//        }
//        byte[] buff = new byte[buffer.limit()];
//        buffer.position(0);
//        buffer.get(buff);
//        synchronized (mBitmapLock) {
//            yuv2RGBBitmap(buff, mBitmap, width, height);
//        }

        buffer.position(0);
        byte[] data = new byte[buffer.capacity()];
        buffer.get(data);
        synchronized (mBitmapLock) {
            mBitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
        }

        if (mBitmap != null) {
            showImage();
        }
    }

    class ImageWorkThread extends Thread {
        @Override
        public void run() {
            while (mIsImageStarted) {
                synchronized (mBitmapLock) {
                    mBitmap = BitmapFactory.decodeFile(LOCAL_IMAGE_PATH);
                    if (mBitmap == null) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, "The picture does not exist!", Toast.LENGTH_SHORT).show();
                            }
                        });
                        break;
                    }
                    if (mIsDetecting) {
                        int size = mBitmap.getByteCount();
                        if (mData == null || mData.capacity() != size) {
                            mData = ByteBuffer.allocateDirect(size);
                        }
                        mData.rewind();
                        Bitmap bitmap = mBitmap.copy(mBitmap.getConfig(), true);
                        mBitmap.copyPixelsToBuffer(mData);
                        mBitmap = bitmap;
                        mDetectedResults = VisionNative.nativeDetect(mData, 1, mBitmap.getWidth(), mBitmap.getHeight());
                    } else {
                        mDetectedResults = null;
                    }
                }
                showImage();
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            clearBitmap();
        }
    }

    private void clearBitmap() {
        synchronized (mBitmapLock) {
            mBitmap = null;
            mDetectedResults = null;
        }
        showImage();
    }

    private void yuv2RGBBitmap(byte[] data, Bitmap bitmap, int width, int height) {
        int frameSize = width * height;
        int[] rgba = new int[frameSize];
        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                int y = (0xff & ((int) data[i * width + j]));
                int v = (0xff & ((int) data[frameSize + (i >> 1) * width + (j & ~1) + 0]));

                int u = (0xff & ((int) data[frameSize + (i >> 1) * width + (j & ~1) + 1]));

                y = y < 16 ? 16 : y;
                int r = Math.round(1.164f * (y - 16) + 1.596f * (v - 128));
                int g = Math.round(1.164f * (y - 16) - 0.813f * (v - 128) - 0.391f * (u - 128));
                int b = Math.round(1.164f * (y - 16) + 2.018f * (u - 128));
                r = r < 0 ? 0 : (r > 255 ? 255 : r);
                g = g < 0 ? 0 : (g > 255 ? 255 : g);
                b = b < 0 ? 0 : (b > 255 ? 255 : b);
                rgba[i * width + j] = 0xff000000 + (b << 16) + (g << 8) + r;
            }
        }
        bitmap.setPixels(rgba, 0, width, 0, 0, width, height);
    }
}
