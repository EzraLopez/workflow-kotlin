package com.squareup.workflow1.ui.backstack

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.os.Parcelable.Creator
import android.util.SparseArray
import android.view.View
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.ViewTreeSavedStateRegistryOwner
import com.squareup.workflow1.ui.WorkflowLifecycleOwner
import com.squareup.workflow1.ui.WorkflowLifecycleOwner.Companion
import com.squareup.workflow1.ui.WorkflowUiExperimentalApi
import org.jetbrains.annotations.TestOnly

/**
 * TODO update kdoc
 * Used by [ViewStateCache] to record the [viewState] data for the view identified
 * by [key], which is expected to match the `toString()` of a
 * [com.squareup.workflow1.ui.Compatible.compatibilityKey].
 */
// TODO unit tests for both state fields
@OptIn(WorkflowUiExperimentalApi::class)
internal class ViewStateFrame private constructor(
  val key: String,
  private var viewState: SparseArray<Parcelable>?,
  private var androidXBundle: Bundle?
) : Parcelable {
  constructor(key: String) : this(key, null, null)

  /**
   * Acts as the [LifecycleOwner][androidx.lifecycle.LifecycleOwner], etc for the backstack frame.
   * This will initially be set by [restoreHierarchyState], and then nulled out again when the frame
   * is hidden (by [destroyOnDetach]), to guard against memory leaks.
   */
  private var savedStateController: WorkflowSavedStateRegistryController? = null

  /**
   * Replaces this frame's state with that from [frame].
   */
  fun loadStateRegistryFrom(frame: ViewStateFrame?) {
    require(savedStateController != null)
    require(frame == null || key == frame.key) {
      "Expected frame's key to match: $key != ${frame!!.key}"
    }

    // We don't need to set viewState since this method is only called when this frame is the
    // current frame, and in that case restoreHierarchyState will have already been called.
    androidXBundle = frame?.androidXBundle
  }

  fun restoreStateRegistry() {
    require(savedStateController != null)
    val controller = ensureSavedStateController()
    println("OMG VSF restoring state: $androidXBundle")
    // Controller must _always_ be restored, even if there's no data, so that consume doesn't
    // throw.
    controller.performRestore(androidXBundle ?: Bundle.EMPTY)
  }

  /**
   * TODO kdoc
   */
  fun installOn(view: View) {
    check(savedStateController == null)
    ensureSavedStateController(view)
  }

  /**
   * Initializes an [WorkflowSavedStateRegistryController] for this frame, installs it as the
   * [ViewTreeSavedStateRegistryOwner], and restores view hierarchy state.
   *
   * This method should be called as soon as the view is created that it owns the state for, and it
   * _must_ be called before [performSave].
   */
  fun restoreHierarchyState(view: View) {
    check(savedStateController != null)
    // ensureSavedStateController(view)

    // When this method is called to restore a previously-hidden view, this will perform the
    // restore. If viewState is null, this call is happening as part of the entire activity restore,
    // and the Android framework will call restoreHierarchyState for us.
    viewState?.let(view::restoreHierarchyState)
  }

  private fun ensureSavedStateController(view: View? = null): WorkflowSavedStateRegistryController {
    if (savedStateController == null) {
      requireNotNull(view)
      val lifecycle = Companion.get(view)!!
      savedStateController = WorkflowSavedStateRegistryController(lifecycle).also { controller ->
        ViewTreeSavedStateRegistryOwner.set(view, controller)
      }
    }
    return savedStateController!!
  }

  /**
   * Saves the SavedStateRegistry, and if [view] is not null, also asks the view to save its own
   * state.
   *
   * [restoreHierarchyState] _must_ be called before this method.
   */
  fun performSave(view: View? = null) {
    androidXBundle = savedStateController!!.performSave()
    if (view != null) {
      viewState = SparseArray<Parcelable>().also {
        view.saveHierarchyState(it)
      }
    }
  }

  fun destroyOnDetach() {
    savedStateController?.destroyOnDetach()
    // Null it out to guard against memory leaks, since this frame instance will persist potentially
    // for a long time while the screen is hidden.
    savedStateController = null
  }

  override fun describeContents(): Int = 0

  override fun writeToParcel(
    parcel: Parcel,
    flags: Int
  ) {
    parcel.writeString(key)
    // viewState may be null here if this is the current frame in a ViewStateCache, in which case
    // the view state isn't managed by this frame but by the regular view tree dispatch.
    @Suppress("UNCHECKED_CAST")
    parcel.writeSparseArray(viewState as SparseArray<Any>?)
    parcel.writeBundle(androidXBundle)
  }

  companion object CREATOR : Creator<ViewStateFrame> {
    private const val FAKE_STATE_KEY = "fakeState"

    override fun createFromParcel(parcel: Parcel): ViewStateFrame {
      val key = parcel.readString()!!
      val classLoader = ViewStateFrame::class.java.classLoader
      val viewState = parcel.readSparseArray<Parcelable>(classLoader)
      val androidXBundle = parcel.readBundle(classLoader)

      return ViewStateFrame(key, viewState, androidXBundle)
    }

    override fun newArray(size: Int): Array<ViewStateFrame?> = arrayOfNulls(size)

    /**
     * Creates a [ViewStateFrame] that holds some nonsense data that can't be restored to views,
     * but can be tested for equality with [equalsForTest].
     */
    internal fun createForTest(
      key: String,
      fakeState: String
    ): ViewStateFrame = ViewStateFrame(
      key,
      viewState = null,
      androidXBundle = Bundle().apply { putString(FAKE_STATE_KEY, fakeState) }
    )

    /**
     * Tests that two [ViewStateFrame]s created with [createForTest] were created with the same
     * `fakeState`.
     */
    internal fun equalsForTest(
      left: ViewStateFrame,
      right: ViewStateFrame
    ): Boolean {
      if (left.key != right.key) return false
      if (left.viewState != null || right.viewState != null) return false
      val leftBundle = left.androidXBundle ?: return false
      val rightBundle = right.androidXBundle ?: return false
      if (leftBundle.size() != 1 || rightBundle.size() != 1) return false
      return leftBundle.getString(FAKE_STATE_KEY) == rightBundle.getString(FAKE_STATE_KEY)
    }
  }
}

/**
 * TODO write documentation
 */
@OptIn(WorkflowUiExperimentalApi::class)
private class WorkflowSavedStateRegistryController(
  lifecycleOwner: WorkflowLifecycleOwner,
) : SavedStateRegistryOwner, WorkflowLifecycleOwner by lifecycleOwner {

  private val savedStateController = SavedStateRegistryController.create(this)

  override fun getSavedStateRegistry(): SavedStateRegistry = savedStateController.savedStateRegistry

  fun performSave(): Bundle = Bundle().also(savedStateController::performSave)
  fun performRestore(bundle: Bundle) {
    savedStateController.performRestore(bundle)
  }
}
