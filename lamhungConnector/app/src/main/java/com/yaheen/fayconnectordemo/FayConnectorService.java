package com.yaheen.fayConnectorDemo;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Arrays;
import java.util.Date;

public class FayConnectorService extends Service {
    private AudioRecord record;
    private int recordBufsize = 0;
    private Socket socket = null;
    private InputStream in = null;
    private OutputStream out = null;
    public static boolean running = false;
    private File cacheDir = null;
    private String channelId = null;
    private  PendingIntent pendingIntent = null;
    private  NotificationManagerCompat notificationManager = null;
    private  long totalrece = 0;
    private long totalsend = 0;
    private AudioManager mAudioManager = null;
    private Thread sendThread = null;
    private Thread receThread = null;
    private  boolean isPlay = false;
    private boolean isMic = false;
    private boolean isRecordStarted = false;
    BroadcastReceiver scoReceiver;


    //创建通知
    private String createNotificationChannel(String channelID, String channelNAME, int level) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(channelID, channelNAME, level);
            manager.createNotificationChannel(channel);
            return channelID;
        } else {
            return null;
        }
    }

    // 定义广播的动作字符串
    public static final String ACTION_CONTROL_MIC = "com.yaheen.fayConnectorDemo.ACTION_CONTROL_MIC";

    private final BroadcastReceiver micControlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean micEnabled = intent.getBooleanExtra("mic", false);
            if (micEnabled) {
                isMic = true;
            } else {
                isMic = false;
            }
        }
    };
    private void startMicrophone() {
        //开启sco
        mAudioManager.startBluetoothSco();
        mAudioManager.setMode(mAudioManager.MODE_IN_CALL);
        mAudioManager.setBluetoothScoOn(true);
        //开始录音
        record.startRecording();
        isRecordStarted = true;
        Log.d("fay", "麦克风启动成功");
    }

    private void stopMicrophone() {
        //关闭sco
        mAudioManager.stopBluetoothSco();
        mAudioManager.setBluetoothScoOn(false);
        mAudioManager.setMode(mAudioManager.MODE_NORMAL);
        //停止录音
        record.stop();
        isRecordStarted = false;
        Log.d("fay", "麦克风关闭成功");
    }



    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, START_FLAG_REDELIVERY, startId);
        isMic = intent.getBooleanExtra("mic", false);
        return Service.START_STICKY;

    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d("fay", "服务启动");

        running = true;
        this.cacheDir = getApplicationContext().getFilesDir();//getCacheDir();

        // 注册广播接收器
        IntentFilter filter = new IntentFilter(ACTION_CONTROL_MIC);
        registerReceiver(micControlReceiver, filter);

        //蓝牙sco状态监听
        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
        scoReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
                if (AudioManager.SCO_AUDIO_STATE_CONNECTED == state) {
                    Log.d("fay", "蓝牙sco连接成功");
                }
                if (AudioManager.SCO_AUDIO_STATE_DISCONNECTED == state) {
                    Log.d("fay", "蓝牙sco关闭");
                }
            }
        };
        this.registerReceiver(scoReceiver, intentFilter);

        //连接socket
        String serverAddress = KVUtils.readData(getApplicationContext(), "ServerAddress");
        if (serverAddress == null || serverAddress.split(":").length != 2) {
            return;
        }

        //启动发送线程
        sendThread = new Thread(new Runnable() {
            @Override
            public void run() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (ContextCompat.checkSelfPermission(FayConnectorService.this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        if (record == null) {
                            recordBufsize = AudioRecord
                                    .getMinBufferSize(16000,
                                            AudioFormat.CHANNEL_IN_MONO,
                                            AudioFormat.ENCODING_PCM_16BIT);
                            record = new AudioRecord(MediaRecorder.AudioSource.MIC,
                                    16000,
                                    AudioFormat.CHANNEL_IN_MONO,
                                    AudioFormat.ENCODING_PCM_16BIT,
                                    recordBufsize);

                        }

                        byte[] data = new byte[1024];
                        Log.d("fay", "开始传输音频");
                        while (running) {
                            try {
                                Thread.sleep(10);
                            }catch (Exception e){}
                            if (socket == null || socket.isClosed()){
                                try {
                                    Thread.sleep(10000);
                                }catch (Exception ee){
                                }
                                reconnectSocket();
                            }
                            if (isPlay){
                                continue;
                            }
                            if (!isMic){
                                if (isRecordStarted) {
                                    stopMicrophone();
                                }
                                continue;
                            }else {
                                if (!isRecordStarted) { //isPlay == true时在上面已经continue
                                    startMicrophone();
                                }
                            }
                            int size = record.read(data, 0, 1024);
                            if (size > 0) {
                                try {
                                    out.write(data);
                                }catch (Exception e){
                                    Log.d("fay", "socket断开10秒后重连");
                                    socket = null;
                                    continue;
                                }
                                totalsend += data.length / 1024;
                            } else {//麦克风被占用了，等待10秒重新录取
                                stopMicrophone();
                                try {
                                    Thread.sleep(10000);
                                } catch (Exception e) {
                                }
                            }
                        }
                        running = false;
                        record.stop();
                        record = null;
                        ((AudioManager) getSystemService(Context.AUDIO_SERVICE)).stopBluetoothSco();
                        try {
                            socket.close();
                        } catch (Exception e) {
                        }
                        socket = null;
                        Log.d("fay", "send线程结束");
                    }
                }

            }
        });
        sendThread.start();

        //启动接收线程
        receThread = new Thread(new Runnable() {
            @Override
            public void run() {
                    while (running) {
                        try {
                            if (socket == null || socket.isClosed()) {
                                try {
                                    Thread.sleep(1000);
                                } catch (Exception e) {
                                }
                                continue;
                            }
                            byte[] data = new byte[9];
                            byte[] wavhead = new byte[]{0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};//文件传输开始标记
                            in.read(data);
                            if (Arrays.equals(wavhead, data)) {
                                Log.d("fay", "开始接收音频文件");
                                String filedata = "";
                                data = new byte[1024];
                                int len = 0;
                                while ((len = in.read(data)) != -1) {
                                    byte[] temp = new byte[len];
                                    System.arraycopy(data, 0, temp, 0, len);
                                    filedata += MainActivity.bytesToHexString(temp);
                                    int index = filedata.indexOf("080706050403020100");
                                    if (filedata.length() > 9 && index > 0) {//wav文件结束标记
                                        filedata = filedata.substring(0, index).replaceAll("F0F1F2F3F4F5F6F7F8", "");
                                        File wavFile = new File(cacheDir, String.format("sample-%s.mp3", new Date().getTime() + ""));
                                        wavFile.createNewFile();
                                        FileOutputStream fos = new FileOutputStream(wavFile);
                                        fos.write(MainActivity.decodeHexBytes(filedata.toCharArray()));
                                        fos.close();
                                        totalrece += filedata.length() / 2 / 1024;
                                        Log.d("fay", "mp3文件接收完成:" + wavFile.getAbsolutePath() + "," + filedata.length() / 2);
                                        try {
                                            MediaPlayer player = new MediaPlayer();
                                            player.setDataSource(wavFile.getAbsolutePath());
                                            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                                                @Override
                                                public void onPrepared(MediaPlayer mp) {
                                                    isPlay = true;
                                                    Log.d("fay", "开始播放");
                                                    if (isRecordStarted) {
                                                        stopMicrophone();
                                                    }
                                                    try {
                                                        Thread.sleep(100);
                                                    } catch (Exception e) {

                                                    }
                                                    isPlay = true;
                                                    mp.start();
                                                }
                                            });
                                            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {

                                                @Override
                                                public void onCompletion(MediaPlayer mp) {
                                                    Log.d("fay", "播放完成");
                                                    mp.release();
                                                    if (isRecordStarted) {
                                                        startMicrophone();
                                                    }
                                                    isPlay = false;
                                                }

                                            });
                                            player.setVolume(1, 1);
                                            player.setLooping(false);
                                            player.prepareAsync();

                                        } catch (IOException e) {
                                            Log.e("fay", e.toString());
                                        }
                                        break;
                                    }

                                }
                                Thread.sleep(300);
                            }
                            Thread.sleep(300);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    Log.d("fay", "rece线程结束");
            }
        });
        receThread.start();

        //通知栏
        new Thread(new Runnable() {
            @Override
            public void run() {
                try{
                    while (running) {
                        Thread.sleep(3000);
                        String statusStr = socket == null ? "正在连接" : "已经连接";
                        if (totalsend + totalrece > 2048){
                            inotify("lamhung connector demo", statusStr + "控制器，累计接收/发送：" + String.format("%.2f", (double)totalrece / 1024) + "/" + String.format("%.2f", (double)totalsend / 1024) + "MB");
                        } else {
                            inotify("lamhung connector demo", statusStr + "控制器，累计接收/发送：" + totalrece + "/" + totalsend + "KB");
                        }
                    }
                    inotify("lamhung connector demo", "已经断开控制器");
                }catch (Exception e){
                    Log.e("fay", e.toString());
                }finally {
                    FayConnectorService.this.stopSelf();
                }
            }
        }).start();


    }

    private void inotify(String title, String content){
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if (pendingIntent == null){
            pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        }
        if (channelId == null){
            channelId = createNotificationChannel("my_channel_ID", "my_channel_NAME", NotificationManager.IMPORTANCE_HIGH);
        }
        if (notificationManager == null){
            notificationManager = NotificationManagerCompat.from(this);
        }
        NotificationCompat.Builder notification2 = new NotificationCompat.Builder(FayConnectorService.this, channelId)
                .setContentTitle(title)
                .setContentText(content)
                .setContentIntent(pendingIntent)
                .setSmallIcon(R.drawable.icon)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);
        //notificationManager.notify(100, notification2.build());
        startForeground(100, notification2.build());
    }


    @Override
    public void onDestroy() {
        Log.d("fay", "服务关闭");
        super.onDestroy();
        running = false;
        if (isRecordStarted){
            stopMicrophone();
        }
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        stopForeground(true);
        unregisterReceiver(micControlReceiver);
        unregisterReceiver(scoReceiver);
    }

    private void reconnectSocket() {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        String serverAddress = KVUtils.readData(getApplicationContext(), "ServerAddress");
        if (serverAddress == null || serverAddress.split(":").length != 2) {
            return;
        }

        try {
            socket = new Socket(serverAddress.split(":")[0], Integer.parseInt(serverAddress.split(":")[1]));
            in = socket.getInputStream();
            out = socket.getOutputStream();
            Log.d("fay", "重新连接  控制器成功");
        } catch (IOException e) {
            Log.e("fay", "重新连接  控制器失败", e);
        }
    }

}
