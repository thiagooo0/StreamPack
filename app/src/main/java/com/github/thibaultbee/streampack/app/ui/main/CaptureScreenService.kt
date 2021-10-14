package com.github.thibaultbee.streampack.app.ui.main

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModelProvider
import com.github.thibaultbee.streampack.app.R


@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
class CaptureScreenService : Service() {
    private var projectionManager: MediaProjectionManager? = null
    private var mMediaProjection: MediaProjection? = null
    private val CHANNEL_ID = "screenCapture"
    private val ONGOING_NOTIFICATION_ID = 100
    private var mScreenCaptureUtils: ScreenCaptureUtils? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }


    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        try {
            if (mScreenCaptureUtils == null) {
                Log.i("srt", "onStartCommand==========")
                val mResultCode = intent.getIntExtra("code", -1)
                val mResultData = intent.getParcelableExtra("data") as Intent?
                val density = intent.getIntExtra("density", -1)
                projectionManager =
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager?
                mMediaProjection =
                    projectionManager?.getMediaProjection(mResultCode, mResultData!!)

                ScreenCaptureFragment.instance.viewModel.startScreenCapture(
                    mMediaProjection!!,
                    density
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun createNotificationChannel() {
        val nfIntent = Intent(this, MainActivity::class.java)
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentIntent(PendingIntent.getActivity(this, 0, nfIntent, 0))
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentText("is running......")
            .build()
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "notification_name",
            NotificationManager.IMPORTANCE_HIGH
        )
        notificationManager.createNotificationChannel(channel)
        startForeground(ONGOING_NOTIFICATION_ID, notification)
    }


    override fun onDestroy() {
        super.onDestroy()
        stopForeground(true)
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }
}