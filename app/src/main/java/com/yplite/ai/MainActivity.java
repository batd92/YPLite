package com.yplite.ai;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import androidx.exifinterface.media.ExifInterface;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.io.FileOutputStream;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = MainActivity.class.getSimpleName();
    public static final int OPEN_GALLERY_REQUEST_CODE = 0;
    public static final int TAKE_PHOTO_REQUEST_CODE = 1;

    public static final int REQUEST_LOAD_MODEL = 0;
    public static final int REQUEST_RUN_MODEL = 1;
    public static final int RESPONSE_LOAD_MODEL_SUCCESSFUL= 0;
    public static final int RESPONSE_LOAD_MODEL_FAILED = 1;
    public static final int RESPONSE_RUN_MODEL_SUCCESSFUL = 2;
    public static final int RESPONSE_RUN_MODEL_FAILED = 3;

    protected ProgressDialog pbLoadModel = null;
    protected ProgressDialog pbRunModel = null;

    protected Handler receiver = null; // Receive messages from worker thread
    protected Handler sender = null; // Send command to worker thread
    protected HandlerThread worker = null; // Worker thread to load&run model

    // UI components of object detection
    protected TextView tvInputSetting;
    protected TextView tvStatus;
    protected ImageView ivInputImage;
    protected TextView tvOutputResult;
    protected TextView tvInferenceTime;
    protected CheckBox cbOpencl;
    protected Spinner spRunMode;

    // Model settings of PaddleOCR
    protected String modelPath = "";
    protected String labelPath = "";
    protected String imagePath = "";
    protected int cpuThreadNum = 1;
    protected String cpuPowerMode = "";
    protected int detLongSize = 960;
    protected float scoreThreshold = 0.1f;
    private String currentPhotoPath;

    protected Predictor predictor = new Predictor();
    protected Detector detector;

    private Bitmap cur_predict_image = null;

    @SuppressLint("HandlerLeak")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        clearPreferences();
        initLayoutOnScreen();
        initHandlers();
        initWorkerThread();
        initYoLov8();
    }

    private void initYoLov8() {
        detector = new Detector(this, getString(R.string.MODEL_YOLO_PATH), getString(R.string.LABEL_YOLO_PATH));
    }

    private void clearPreferences() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.clear();
        editor.apply();
    }

    // Setup the UI components
    private void initLayoutOnScreen() {
        tvInputSetting = findViewById(R.id.tv_input_setting);
        cbOpencl = findViewById(R.id.cb_opencl);
        tvStatus = findViewById(R.id.tv_model_img_status);
        ivInputImage = findViewById(R.id.iv_input_image);
        tvInferenceTime = findViewById(R.id.tv_inference_time);
        tvOutputResult = findViewById(R.id.tv_output_result);
        spRunMode = findViewById(R.id.sp_run_mode);
        tvInputSetting.setMovementMethod(ScrollingMovementMethod.getInstance());
        tvOutputResult.setMovementMethod(ScrollingMovementMethod.getInstance());
    }

    @SuppressLint("HandlerLeak")
    private void initHandlers() {
        receiver = new Handler() {
            @Override
            public void handleMessage(@NonNull Message msg) {
                handleReceiverMessage(msg);
            }
        };
    }

    private void initWorkerThread() {
        worker = new HandlerThread("Predictor Worker");
        worker.start();
        sender = new Handler(worker.getLooper()) {
            @Override
            public void handleMessage(@NonNull Message msg) {
                handleSenderMessage(msg);
            }
        };
    }

    /**
     * WorkerThread Receiver Message
     * @param msg
     */
    private void handleReceiverMessage(@NonNull Message msg) {
        switch (msg.what) {
            case RESPONSE_LOAD_MODEL_SUCCESSFUL:
                dismissProgressDialog(pbLoadModel);
                onLoadModelSuccess();
                break;
            case RESPONSE_LOAD_MODEL_FAILED:
                dismissProgressDialog(pbLoadModel);
                Toast.makeText(MainActivity.this, "Load model failed!", Toast.LENGTH_SHORT).show();
                onLoadModelFailed();
                break;
            case RESPONSE_RUN_MODEL_SUCCESSFUL:
                dismissProgressDialog(pbRunModel);
                onRunModelSuccess();
                break;
            case RESPONSE_RUN_MODEL_FAILED:
                dismissProgressDialog(pbRunModel);
                Toast.makeText(MainActivity.this, "Run model failed!", Toast.LENGTH_SHORT).show();
                onRunModelFailed();
                break;
            default:
                break;
        }
    }

    /**
     * WorkerThread Receiver Request
     * @param msg
     */
    private void handleSenderMessage(@NonNull Message msg) {
        int responseMessage;
        switch (msg.what) {
            case REQUEST_LOAD_MODEL:
                responseMessage = onLoadModel() ? RESPONSE_LOAD_MODEL_SUCCESSFUL : RESPONSE_LOAD_MODEL_FAILED;
                break;
            case REQUEST_RUN_MODEL:
                responseMessage = onRunModel() ? RESPONSE_RUN_MODEL_SUCCESSFUL : RESPONSE_RUN_MODEL_FAILED;
                break;
            default:
                return;
        }
        receiver.sendEmptyMessage(responseMessage);
    }

    private void dismissProgressDialog(ProgressDialog progressDialog) {
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
    }

    @Override
    @SuppressLint("SetTextI18n")
    protected void onResume() {
        super.onResume();
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        // Check if settings have changed
        boolean settingsChanged = checkSettingsChanged(sharedPreferences);
        boolean modelSettingsChanged = checkModelSettingsChanged(sharedPreferences);

        // Update settings if they have changed
        if (settingsChanged) {
            updateSettings(sharedPreferences);
            set_img();
        }

        // Reload model if model settings have changed
        if (modelSettingsChanged) {
            updateModelSettings(sharedPreferences);
            updateUI();
            loadModel();
        }
    }

    // Check if general settings have changed
    private boolean checkSettingsChanged(SharedPreferences sharedPreferences) {
        boolean settingsChanged = false;

        String label_path = sharedPreferences.getString(getString(R.string.LABEL_PATH_KEY), getString(R.string.LABEL_PATH_DEFAULT));
        String image_path = sharedPreferences.getString(getString(R.string.IMAGE_PATH_KEY), getString(R.string.IMAGE_PATH_DEFAULT));
        int det_long_size = Integer.parseInt(sharedPreferences.getString(getString(R.string.DET_LONG_SIZE_KEY), getString(R.string.DET_LONG_SIZE_DEFAULT)));
        float score_threshold = Float.parseFloat(sharedPreferences.getString(getString(R.string.SCORE_THRESHOLD_KEY), getString(R.string.SCORE_THRESHOLD_DEFAULT)));

        settingsChanged |= !label_path.equalsIgnoreCase(labelPath);
        settingsChanged |= !image_path.equalsIgnoreCase(imagePath);
        settingsChanged |= det_long_size != detLongSize;
        settingsChanged |= scoreThreshold != score_threshold;

        return settingsChanged;
    }

    /**
     * PaddleOCR: Check if model settings have changed
     * @param sharedPreferences
     * @return
     */
    private boolean checkModelSettingsChanged(SharedPreferences sharedPreferences) {
        boolean modelSettingsChanged = false;

        String model_path = sharedPreferences.getString(getString(R.string.MODEL_PATH_KEY), getString(R.string.MODEL_PATH_DEFAULT));
        int cpu_thread_num = Integer.parseInt(sharedPreferences.getString(getString(R.string.CPU_THREAD_NUM_KEY), getString(R.string.CPU_THREAD_NUM_DEFAULT)));
        String cpu_power_mode = sharedPreferences.getString(getString(R.string.CPU_POWER_MODE_KEY), getString(R.string.CPU_POWER_MODE_DEFAULT));

        modelSettingsChanged |= !model_path.equalsIgnoreCase(modelPath);
        modelSettingsChanged |= cpu_thread_num != cpuThreadNum;
        modelSettingsChanged |= !cpu_power_mode.equalsIgnoreCase(cpuPowerMode);

        return modelSettingsChanged;
    }

    /**
     * PaddleOCR: Update general settings
     * @param sharedPreferences
     * @return
     */

    private void updateSettings(SharedPreferences sharedPreferences) {
        labelPath = sharedPreferences.getString(getString(R.string.LABEL_PATH_KEY), getString(R.string.LABEL_PATH_DEFAULT));
        imagePath = sharedPreferences.getString(getString(R.string.IMAGE_PATH_KEY), getString(R.string.IMAGE_PATH_DEFAULT));
        detLongSize = Integer.parseInt(sharedPreferences.getString(getString(R.string.DET_LONG_SIZE_KEY), getString(R.string.DET_LONG_SIZE_DEFAULT)));
        scoreThreshold = Float.parseFloat(sharedPreferences.getString(getString(R.string.SCORE_THRESHOLD_KEY), getString(R.string.SCORE_THRESHOLD_DEFAULT)));
    }

    // Update model settings
    private void updateModelSettings(SharedPreferences sharedPreferences) {
        modelPath = sharedPreferences.getString(getString(R.string.MODEL_PATH_KEY), getString(R.string.MODEL_PATH_DEFAULT));
        cpuThreadNum = Integer.parseInt(sharedPreferences.getString(getString(R.string.CPU_THREAD_NUM_KEY), getString(R.string.CPU_THREAD_NUM_DEFAULT)));
        cpuPowerMode = sharedPreferences.getString(getString(R.string.CPU_POWER_MODE_KEY), getString(R.string.CPU_POWER_MODE_DEFAULT));
    }

    // Update the UI to reflect the new settings
    @SuppressLint("SetTextI18n")
    private void updateUI() {
        tvInputSetting.setText("Model: " + modelPath.substring(modelPath.lastIndexOf("/") + 1) + "\nOPENCL: " + cbOpencl.isChecked() + "\nCPU Thread Num: " + cpuThreadNum + "\nCPU Power Mode: " + cpuPowerMode);
        tvInputSetting.scrollTo(0, 0);
    }

    public void loadModel() {
        pbLoadModel = ProgressDialog.show(this, "", "loading model...", false, false);
        sender.sendEmptyMessage(REQUEST_LOAD_MODEL);
    }

    public void runModel() {
        pbRunModel = ProgressDialog.show(this, "", "running model...", false, false);
        sender.sendEmptyMessage(REQUEST_RUN_MODEL);
    }

    public boolean onLoadModel() {
        if (predictor.isLoaded()) {
            predictor.releaseModel();
        }
        return predictor.init(MainActivity.this, modelPath, labelPath, cbOpencl.isChecked() ? 1 : 0, cpuThreadNum,
                cpuPowerMode,
                detLongSize, scoreThreshold);
    }

    /**
     * PaddleOCR: Predictor
     * @return
     */
    public boolean onRunModel() {
        // Detect by yolo -> paddler ocr
        Bitmap image = ((BitmapDrawable) ivInputImage.getDrawable()).getBitmap();
        if (image == null) {
            tvStatus.setText("STATUS: image is not exists");
            return false;
        }
        List<Integer> modes = getRunModesPaddleOCR();
        // Log the image and modes
        Log.d("onRunModel", "Image and modes are ready");

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            List<BoundingBox> bestBoxes = detector.detect(image);
            if (bestBoxes != null) {
                Log.d("onRunModel", "Bounding boxes detected: " + bestBoxes.size());

                List<Bitmap> croppedImages = cropBoundingBoxes(image, bestBoxes);
                Log.d("onRunModel", "Cropped images created: " + croppedImages.size());

                for (Bitmap croppedImage : croppedImages) {
                    saveImageToStorage(croppedImage);
                    predictor.setInputImage(croppedImage);
                    try {
                        if (predictor.isLoaded()) {
                            // Run PaddleOCR model
                            predictor.runModel(modes.get(0), modes.get(1), modes.get(2));
                            BoundingBox box = bestBoxes.get(croppedImages.indexOf(croppedImage));
                            if (box != null && predictor.outputResult() != null) {
                                box.setValueLabel(predictor.outputResult());
                            }
                        } else {
                            tvStatus.setText("STATUS: predictor not loaded");
                            Log.e("onRunModel", "Predictor not loaded");
                            return false;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        tvStatus.setText("STATUS: error running model: " + e.getMessage());
                        Log.e("onRunModel", "Error running model", e);
                        return false;
                    }
                }

                // Draw bounding boxes
                OverlayView overlayView = findViewById(R.id.overlayView);
                overlayView.setResults(bestBoxes);
                return true;
            }
        }
        return false;
    }

    private void saveImageToStorage(Bitmap bitmap) {
        String fileName = "cropped_image_" + System.currentTimeMillis() + ".png"; // Generate unique file name
        File storageDir = getApplicationContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File imageFile = new File(storageDir, fileName);

        try {
            FileOutputStream fos = new FileOutputStream(imageFile);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos); // Compress bitmap into PNG format
            fos.close();
            Log.d("saveImageToStorage", "Image saved to: " + imageFile.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
            Log.e("saveImageToStorage", "Error saving image: " + e.getMessage());
        }
    }

    private List<Integer> getRunModesPaddleOCR() {
        String run_mode = spRunMode.getSelectedItem().toString();
        List<Integer> results = new ArrayList<>();

        int run_det = run_mode.contains("D") ? 1 : 0;
        int run_cls = run_mode.contains("C") ? 1 : 0;
        int run_rec = run_mode.contains("R") ? 1 : 0;

        results.add(run_det);
        results.add(run_cls);
        results.add(run_rec);

        return results;
    }


    @SuppressLint("SetTextI18n")
    public void onLoadModelSuccess() {
        // Load test image from path and run model
        tvInputSetting.setText("Model: " + modelPath.substring(modelPath.lastIndexOf("/") + 1) + "\nOPENCL: " + cbOpencl.isChecked() + "\nCPU Thread Num: " + cpuThreadNum + "\nCPU Power Mode: " + cpuPowerMode);
        tvInputSetting.scrollTo(0, 0);
        tvStatus.setText("STATUS: load model success");

    }

    @SuppressLint("SetTextI18n")
    public void onLoadModelFailed() {
        tvStatus.setText("STATUS: load model failed");
    }

    /**
     * Load UI when detect success
     */
    @SuppressLint("SetTextI18n")
    public void onRunModelSuccess() {
        tvStatus.setText("STATUS: run model success");
        // Obtain results and update UI
        tvInferenceTime.setText("Inference time: " + predictor.inferenceTime() + " ms");
        Bitmap outputImage = predictor.outputImage();
        if (outputImage != null) {
            ivInputImage.setImageBitmap(outputImage);
        }
        tvOutputResult.setText(predictor.outputResult());
        tvOutputResult.scrollTo(0, 0);
    }

    @SuppressLint("SetTextI18n")
    public void onRunModelFailed() {
        tvStatus.setText("STATUS: run model failed");
    }

    public void set_img() {
        try {
            AssetManager assetManager = getAssets();
            InputStream in = assetManager.open(imagePath);
            Bitmap bmp = BitmapFactory.decodeStream(in);
            cur_predict_image = bmp;
            ivInputImage.setImageBitmap(bmp);
        } catch (IOException e) {
            Toast.makeText(MainActivity.this, "Load image failed!", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    public void onSettingsClicked() {
        startActivity(new Intent(MainActivity.this, SettingsActivity.class));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_action_options, menu);
        return true;
    }

    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean isLoaded = predictor.isLoaded();
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                return true;
            case 100:
                if (requestAllPermissions()) {
                    onSettingsClicked();
                }
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length >= 2) {
            if (grantResults[0] != PackageManager.PERMISSION_GRANTED || grantResults[1] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean requestAllPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.CAMERA},
                    0);
            return false;
        }
        return true;
    }

    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK, null);
        intent.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
        startActivityForResult(intent, OPEN_GALLERY_REQUEST_CODE);
    }

    private void takePhoto() {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        // Ensure that there's a camera activity to handle the intent
        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
            // Create the File where the photo should go
            File photoFile = null;
            try {
                photoFile = createImageFile();
            } catch (IOException ex) {
                Log.e("Main activity", ex.getMessage(), ex);
                Toast.makeText(MainActivity.this,
                        "Create Camera temp file failed: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
            }
            // Continue only if the File was successfully created
            if (photoFile != null) {
                Log.i(TAG, "FILEPATH " + getExternalFilesDir("Pictures").getAbsolutePath());
                Uri photoURI = FileProvider.getUriForFile(this,
                        "com.example.paddlelite.fileprovider",
                        photoFile);
                currentPhotoPath = photoFile.getAbsolutePath();
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI);
                startActivityForResult(takePictureIntent, TAKE_PHOTO_REQUEST_CODE);
                Log.i(TAG, "startActivityForResult finished");
            }
        }

    }

    private File createImageFile() throws IOException {
        // Create an image file name
        @SuppressLint("SimpleDateFormat") String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);

        return File.createTempFile(
                imageFileName,  /* prefix */
                ".bmp",         /* suffix */
                storageDir      /* directory */
        );
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK) {
            switch (requestCode) {
                case OPEN_GALLERY_REQUEST_CODE:
                    if (data == null) {
                        break;
                    }
                    try {
                        ContentResolver resolver = getContentResolver();
                        Uri uri = data.getData();
                        Bitmap image = MediaStore.Images.Media.getBitmap(resolver, uri);
                        String[] proj = {MediaStore.Images.Media.DATA};
                        Cursor cursor = managedQuery(uri, proj, null, null, null);
                        cursor.moveToFirst();
                        if (image != null) {
                            cur_predict_image = image;
                            ivInputImage.setImageBitmap(image);
                        }
                    } catch (IOException e) {
                        Log.e(TAG, e.toString());
                    }
                    break;
                case TAKE_PHOTO_REQUEST_CODE:
                    if (currentPhotoPath != null) {
                        ExifInterface exif = null;
                        try {
                            exif = new ExifInterface(currentPhotoPath);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        assert exif != null;
                        int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_UNDEFINED);
                        Log.i(TAG, "rotation " + orientation);
                        Bitmap image = BitmapFactory.decodeFile(currentPhotoPath);
                        image = Utils.rotateBitmap(image, orientation);
                        if (image != null) {
                            cur_predict_image = image;
                            ivInputImage.setImageBitmap(image);
                        }
                    } else {
                        Log.e(TAG, "currentPhotoPath is null");
                    }
                    break;
                default:
                    break;
            }
        }
    }

    public void btn_reset_img_click(View view) {
        ivInputImage.setImageBitmap(cur_predict_image);
        OverlayView overlayView = findViewById(R.id.overlayView);
        overlayView.setResults(null);
        tvStatus.setText("STATUS: N/A ");
    }

    @SuppressLint("SetTextI18n")
    public void cb_opencl_click(View view) {
        tvStatus.setText("STATUS: load model ......");
        loadModel();
    }

    @SuppressLint("SetTextI18n")
    public void btn_run_model_click(View view) {
        if (!predictor.isLoaded()) {
            tvStatus.setText("STATUS: model is not loaded");
            return;
        }
        runModel();
    }

    private List<Bitmap> cropBoundingBoxes(Bitmap image, List<BoundingBox> boxes) {
        List<Bitmap> croppedImages = new ArrayList<>();

        for (BoundingBox box : boxes) {
            int left = (int) (box.getX1() * image.getWidth());
            int top = (int) (box.getY1() * image.getHeight());
            int right = (int) (box.getX2() * image.getWidth());
            int bottom = (int) (box.getY2() * image.getHeight());

            left = Math.max(0, left);
            top = Math.max(0, top);
            right = Math.min(image.getWidth(), right);
            bottom = Math.min(image.getHeight(), bottom);

            int width = right - left;
            int height = bottom - top;

            Bitmap croppedImage = Bitmap.createBitmap(image, left, top, width, height);

            int scaledWidth = width;
            int scaledHeight = height;
            Bitmap scaledCroppedImage = Bitmap.createScaledBitmap(croppedImage, scaledWidth, scaledHeight, true);

            croppedImages.add(scaledCroppedImage);
        }
        return croppedImages;
    }

    public void btn_choice_img_click(View view) {
        if (requestAllPermissions()) {
            openGallery();
        }
    }

    public void btn_take_photo_click(View view) {
        if (requestAllPermissions()) {
            takePhoto();
        }
    }

    @Override
    protected void onDestroy() {
        if (predictor != null) {
            predictor.releaseModel();
        }
        worker.quit();
        super.onDestroy();
    }
}
