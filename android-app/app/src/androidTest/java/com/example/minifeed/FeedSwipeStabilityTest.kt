package com.example.minifeed

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.recyclerview.widget.RecyclerView
import org.junit.Test

class FeedSwipeStabilityTest {
    @Test
    fun performsTwoHundredVerticalSwipesWithoutCrash() {
        ActivityScenario.launch(MainActivity::class.java).use {
            repeat(200) {
                onView(isAssignableFrom(RecyclerView::class.java)).perform(swipeUp())
            }
        }
    }
}
