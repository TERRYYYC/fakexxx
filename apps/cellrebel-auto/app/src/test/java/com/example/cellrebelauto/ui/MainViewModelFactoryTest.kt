package com.example.cellrebelauto.ui

import android.app.Application
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * F-16 killing regression: the default ViewModelProvider.Factory resolves AndroidViewModel
 * subclasses via reflection on `<init>(Application)`. Kotlin default parameters alone do NOT
 * generate that single-arg constructor — only `@JvmOverloads` does.
 *
 * This test is the "kill switch" for the annotation: removing `@JvmOverloads` from
 * MainViewModel's constructor causes NoSuchMethodException here, turning the test RED.
 *
 * # 杀死"删掉 @JvmOverloads"回归的测试：反射查单参构造，和默认工厂走同一条路
 */
@RunWith(RobolectricTestRunner::class)
class MainViewModelFactoryTest {

    @Test
    fun `default factory single-arg Application constructor exists (F-16 regression guard)`() {
        // The default ViewModelProvider.AndroidViewModelFactory resolves constructors by
        // reflection: it looks for <init>(Application). This is exactly what we test.
        val ctor = MainViewModel::class.java.getConstructor(Application::class.java)
        assertNotNull(
            "MainViewModel must have a single-arg (Application) constructor for the default " +
                "ViewModelProvider.Factory. Add @JvmOverloads to the constructor declaration.",
            ctor
        )
        // Instantiate to prove it doesn't throw — the constructor must actually work,
        // not just exist.
        val app = RuntimeEnvironment.getApplication()
        val vm = ctor.newInstance(app)
        assertNotNull("MainViewModel instance must be non-null", vm)
    }

    @Test
    fun `removing JvmOverloads breaks default factory (killing mutation proof)`() {
        // This test documents the EXACT failure mode: without @JvmOverloads, Kotlin generates
        // only <init>(Application, AppDatabase) and <init>(Application, AppDatabase, int,
        // DefaultConstructorMarker) — neither matches the factory's reflection query.
        //
        // To verify this test kills the mutation:
        //   1. Remove @JvmOverloads from MainViewModel
        //   2. This test throws NoSuchMethodException at getConstructor()
        //   3. Test goes RED
        //
        // We verify the two-arg constructor also exists (it always does) to prove the test
        // is discriminating: the single-arg form is what the annotation generates.
        val twoArgCtor = MainViewModel::class.java.constructors.find { c ->
            c.parameterTypes.let { params ->
                params.size >= 2 &&
                    Application::class.java.isAssignableFrom(params[0]) &&
                    com.example.cellrebelauto.db.AppDatabase::class.java.isAssignableFrom(params[1])
            }
        }
        assertNotNull(
            "Two-arg constructor (Application, AppDatabase) must exist regardless of @JvmOverloads",
            twoArgCtor
        )

        // The discriminator: single-arg only exists WITH @JvmOverloads
        val singleArgCtor = MainViewModel::class.java.constructors.find { c ->
            c.parameterTypes.let { params ->
                params.size == 1 && Application::class.java.isAssignableFrom(params[0])
            }
        }
        assertNotNull(
            "Single-arg (Application) constructor missing — @JvmOverloads annotation required on MainViewModel",
            singleArgCtor
        )
    }
}
