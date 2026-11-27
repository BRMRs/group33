package com.group33.medicationreminder

import android.os.SystemClock
import android.view.View
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import org.hamcrest.Matcher

/** Wait for session restore and the next layout traversal, without a fixed sleep. */
internal fun awaitLoginLayout(id: Int) {
    onView(isRoot()).perform(object : ViewAction {
        override fun getConstraints(): Matcher<View> = isRoot()
        override fun getDescription() = "wait for the login control to be ready and laid out"
        override fun perform(controller: UiController, root: View) {
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < deadline) {
                val target = root.findViewById<View>(id)
                if (target != null && target.isShown && target.isEnabled && target.width > 0 && target.height > 0 && !target.isLayoutRequested) return
                controller.loopMainThreadForAtLeast(16)
            }
            val target = root.findViewById<View>(id)
            throw AssertionError("Login control not ready: $id, shown=${target?.isShown}, enabled=${target?.isEnabled}, size=${target?.width}x${target?.height}")
        }
    })
}

/** NestedScrollView's smooth scroll and the IME transition are not Espresso idlers. */
internal val settleLoginScroll = object : ViewAction {
    override fun getConstraints(): Matcher<View> = androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(View::class.java)
    override fun getDescription() = "allow the keyboard and login scroll animation to settle"
    override fun perform(controller: UiController, view: View) { controller.loopMainThreadForAtLeast(500) }
}
