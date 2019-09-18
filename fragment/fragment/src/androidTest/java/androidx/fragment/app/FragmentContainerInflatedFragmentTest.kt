/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.fragment.app

import androidx.fragment.app.test.FragmentTestActivity
import androidx.fragment.test.R
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.testutils.withActivity
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class FragmentContainerInflatedFragmentTest {

    @Test
    fun testContentViewWithInflatedFragment() {
        // The StrictViewFragment runs the appropriate checks to make sure
        // we're moving through the states appropriately
        with(ActivityScenario.launch(FragmentTestActivity::class.java)) {
            val fm = withActivity {
                setContentView(R.layout.inflated_fragment_container_view)
                supportFragmentManager
            }
            val fragment = fm.findFragmentByTag("fragment1")
            assertThat(fragment).isNotNull()
        }
    }

    @Test
    fun testContentViewWithNoID() {
        with(ActivityScenario.launch(FragmentTestActivity::class.java)) {
            withActivity {
                try {
                    setContentView(R.layout.fragment_container_view_no_id)
                } catch (e: Exception) {
                    assertThat(e)
                        .hasMessageThat()
                        .contains(
                            "FragmentContainerView must have an android:id to add Fragment " +
                                    "androidx.fragment.app.StrictViewFragment with tag " +
                                    "no_id_fragment."
                        )
                }
            }
        }
    }

    @Test
    fun addInflatedFragmentToParentChildFragmentManager() {
        with(ActivityScenario.launch(InflatedContainerActivity::class.java)) {
            val parent = InflatedParentFragment()

            withActivity {
                supportFragmentManager.beginTransaction()
                    .add(R.id.fragmentContainer, parent)
                    .commitNow()
            }

            val child = parent.childFragmentManager.findFragmentByTag("fragment1")

            assertThat(child).isNotNull()
        }
    }

    @Test
    fun addInflatedFragmentToGrandParentChildFragmentManager() {
        with(ActivityScenario.launch(InflatedContainerActivity::class.java)) {
            val grandParent = InflatedParentFragment()
            withActivity {
                supportFragmentManager.beginTransaction()
                    .add(R.id.fragmentContainer, grandParent)
                    .commitNow()
            }

            val parent = StrictViewFragment(R.layout.fragment_container_view)

            withActivity {
                grandParent.childFragmentManager
                    .beginTransaction()
                    .add(R.id.fragment_container_view, parent)
                    .commitNow()
            }

            val grandChild = grandParent.childFragmentManager.findFragmentByTag("fragment1")

            assertThat(grandChild).isNotNull()
        }
    }

    @Test
    fun addInflatedAfterRestore() {
        with(ActivityScenario.launch(InflatedContainerActivity::class.java)) {
            val parent = InflatedParentFragment()

            withActivity {
                supportFragmentManager.beginTransaction()
                    .add(R.id.fragmentContainer, parent, "parent")
                    .commitNow()
            }

            val childFragmentManager = parent.childFragmentManager
            val child = childFragmentManager.findFragmentByTag("fragment1")

            assertThat(childFragmentManager.fragments.count()).isEqualTo(1)

            recreate()

            val recreatedParent = withActivity {
                supportFragmentManager.findFragmentByTag("parent")!!
            }
            val recreatedChildFragmentManager = recreatedParent.childFragmentManager
            val recreatedChild = recreatedChildFragmentManager.findFragmentByTag("fragment1")

            assertThat(recreatedChildFragmentManager.fragments.count()).isEqualTo(1)
            assertThat(recreatedChild).isNotSameInstanceAs(child)
            assertThat(recreatedChild).isNotNull()
        }
    }
}

class InflatedContainerActivity : FragmentActivity(R.layout.simple_container)

class InflatedParentFragment : StrictViewFragment(R.layout.inflated_fragment_container_view)
