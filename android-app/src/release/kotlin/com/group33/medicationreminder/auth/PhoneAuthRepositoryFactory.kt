package com.group33.medicationreminder.auth

/** Release builds cannot select or contain the local demonstration adapter. */
internal fun createPhoneAuthRepository(localDemoRequested: Boolean): PhoneAuthRepository =
    UnconfiguredPhoneAuthRepository
