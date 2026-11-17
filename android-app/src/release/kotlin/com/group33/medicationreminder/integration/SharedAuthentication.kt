package com.group33.medicationreminder.integration

import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity

/** Production phone binding is separate. Release never registers anonymous login or a test identity. */
internal fun installSharedAuthentication(activity: AppCompatActivity, host: ViewGroup) = Unit
