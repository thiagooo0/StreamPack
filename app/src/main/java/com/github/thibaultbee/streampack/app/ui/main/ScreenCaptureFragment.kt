/*
 * Copyright (C) 2021 Thibault B.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.thibaultbee.streampack.app.ui.main

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.content.ContextCompat.startForegroundService
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.github.thibaultbee.streampack.app.databinding.MainFragmentBinding
import com.github.thibaultbee.streampack.app.databinding.ScreenCaptureFragmentBinding
import com.github.thibaultbee.streampack.app.utils.DialogUtils
import com.github.thibaultbee.streampack.app.utils.PreviewUtils.Companion.chooseBigEnoughSize
import com.github.thibaultbee.streampack.utils.getCameraOutputSizes
import com.jakewharton.rxbinding4.view.clicks
import com.tbruyelle.rxpermissions3.RxPermissions
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import java.util.concurrent.TimeUnit


class ScreenCaptureFragment : Fragment() {
    private val TAG = this::class.java.simpleName
    private val fragmentDisposables = CompositeDisposable()
    private lateinit var binding: ScreenCaptureFragmentBinding
    private val REQUEST_MEDIA_PROJECTION = 1
    private val REQUEST_PERMISSION_CODE = 2

    private var isInitialScreenCapture = false

    companion object {
        val instance: ScreenCaptureFragment = ScreenCaptureFragment()
    }

    public val viewModel: ScreenCaptureModel by lazy {
        ViewModelProvider(this).get(ScreenCaptureModel::class.java)
    }

    private val rxPermissions: RxPermissions by lazy { RxPermissions(this) }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = ScreenCaptureFragmentBinding.inflate(inflater, container, false)
        bindProperties()
        return binding.root
    }

    @SuppressLint("MissingPermission")
    private fun bindProperties() {
        binding.liveButton.clicks()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe {
                rxPermissions
                    .requestEachCombined(*viewModel.streamAdditionalPermissions.toTypedArray())
                    .subscribe { permission ->
                        if (!permission.granted) {
                            binding.liveButton.isChecked = false
                            showPermissionError()
                        } else {
                            if (binding.liveButton.isChecked) {
                                viewModel.startStream()
                            } else {
                                viewModel.stopStream()
                            }
                        }
                    }
            }
            .let(fragmentDisposables::add)

        viewModel.streamerError.observe(viewLifecycleOwner) {
            showError("Oops", it)
        }
    }

    private fun showPermissionError() {
        binding.liveButton.isChecked = false
        DialogUtils.showPermissionAlertDialog(requireContext())
    }

    private fun showPermissionErrorAndFinish() {
        binding.liveButton.isChecked = false
        DialogUtils.showPermissionAlertDialog(requireContext()) { requireActivity().finish() }
    }

    private fun showError(title: String, message: String) {
        binding.liveButton.isChecked = false
        viewModel.stopStream()
        DialogUtils.showAlertDialog(requireContext(), "Error: $title", message)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().requestedOrientation =
            ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        // This initiates a prompt dialog for the user to confirm screen projection.
    }

    @SuppressLint("MissingPermission")
    override fun onStart() {
        super.onStart()

        rxPermissions
            .requestEachCombined(Manifest.permission.RECORD_AUDIO)
            .subscribe { permission ->
                if (!permission.granted) {
                    showPermissionErrorAndFinish()
                } else {
                    viewModel.createStreamer()
                    if (!isInitialScreenCapture) {
                        isInitialScreenCapture = true
                        startActivityForResult(
                            (activity?.getSystemService(AppCompatActivity.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent(),
                            REQUEST_MEDIA_PROJECTION
                        )
                    }
                }
            }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode != AppCompatActivity.RESULT_OK) {
                return
            }
            val intent = Intent(activity, CaptureScreenService::class.java)
            intent.putExtra("code", resultCode)
            intent.putExtra("data", data)
            val metrics = DisplayMetrics()
            activity?.windowManager?.defaultDisplay?.getMetrics(metrics)
            intent.putExtra("density", metrics.densityDpi)
            startForegroundService(requireContext(), intent)
        }
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        fragmentDisposables.clear()
    }
}
