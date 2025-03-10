package com.example.myapplication.ultils

import android.app.Activity

fun uiThreadOperations(activity: Activity, updateView: () -> Unit) {
    activity.runOnUiThread {
        updateView()
    }
}