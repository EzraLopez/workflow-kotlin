package com.squareup.workflow1.ui.backstack

import android.content.ContextWrapper
import android.os.Bundle
import android.view.View
import android.view.View.OnAttachStateChangeListener
import androidx.annotation.VisibleForTesting
import androidx.annotation.VisibleForTesting.PRIVATE
import androidx.lifecycle.Lifecycle.Event
import androidx.lifecycle.Lifecycle.Event.ON_CREATE
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.SavedStateRegistry.SavedStateProvider
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.ViewTreeSavedStateRegistryOwner
import com.squareup.workflow1.ui.Named
import com.squareup.workflow1.ui.WorkflowUiExperimentalApi
import com.squareup.workflow1.ui.getRendering

/**
 * Handles persistence chores for container views that manage a set of [Named] renderings,
 * showing a view for one at a time -- think back stacks or tab sets.
 *
 * TODO update kdoc with new contract
 */
@WorkflowUiExperimentalApi
public class ViewStateCache
@VisibleForTesting(otherwise = PRIVATE)
internal constructor(
  @VisibleForTesting(otherwise = PRIVATE)
  internal val hiddenViewStates: MutableMap<String, ViewStateFrame>
) {
  public constructor() : this(mutableMapOf())

  /**
   * The [ViewStateFrame] that holds the state for the last screen shown by [update]. We need to
   * have a reference ot this frame because it needs to have its SavedStateRegistry saved whenever
   * [saveToBundle] is called.
   */
  private var currentFrame: ViewStateFrame? = null

  // TODO replace with state enum
  private var isInstalled = false
  private var isRestored = false
  private var parentSavedStateRegistryOwner: SavedStateRegistryOwner? = null

  /**
   * To be called when the set of hidden views changes but the visible view remains
   * the same. Any cached view state held for renderings that are not
   * [compatible][com.squareup.workflow1.ui.compatible] those in [retaining] will be dropped.
   */
  public fun prune(retaining: Collection<Named<*>>) {
    require(isInstalled)
    pruneKeys(retaining.map { it.compatibilityKey })
  }

  private fun pruneKeys(retaining: Collection<String>) {
    val deadKeys = hiddenViewStates.keys - retaining
    hiddenViewStates -= deadKeys
  }

  private inner class ViewStateListener(viewId: Int) : OnAttachStateChangeListener,
    LifecycleEventObserver,
    SavedStateProvider {
    // This is the same key format that AndroidComposeView uses.
    private val stateRegistryKey = "${ViewStateCache::class.java.simpleName}:$viewId"

    override fun onViewAttachedToWindow(v: View) {
      parentSavedStateRegistryOwner = requireNotNull(v.savedStateRegistryOwnerOrNull()) {
        "Expected to find either a ViewTreeSavedStateRegistryOwner in the view tree, or a " +
          "SavedStateRegistryOwner in the Context chain."
      }

      // We can only restore once, so if we're already restored we don't care.
      if (isRestored) return

      // This will always fire onStateChanged at least once to notify it of the current state.
      parentSavedStateRegistryOwner!!.lifecycle.addObserver(this)
      parentSavedStateRegistryOwner!!.savedStateRegistry
        // TODO add unit test that fails when these keys are not unique
        .registerSavedStateProvider(stateRegistryKey, this)
    }

    override fun onViewDetachedFromWindow(v: View) {
      parentSavedStateRegistryOwner!!.savedStateRegistry.unregisterSavedStateProvider(
        stateRegistryKey
      )
      parentSavedStateRegistryOwner!!.lifecycle.removeObserver(this)
      parentSavedStateRegistryOwner = null
    }

    override fun onStateChanged(
      source: LifecycleOwner,
      event: Event
    ) {
      if (event == ON_CREATE) {
        // We can now read our parent's saved state registry.
        val registry = parentSavedStateRegistryOwner!!.savedStateRegistry
        val restoredBundle = registry.consumeRestoredStateForKey(stateRegistryKey)
        restoreFromBundle(restoredBundle)
        parentSavedStateRegistryOwner!!.lifecycle.removeObserver(this)
      }
    }

    override fun saveState(): Bundle = saveToBundle()
  }

  private lateinit var viewStateListener: ViewStateListener

  /**
   * TODO kdoc
   */
  public fun installOn(view: View) {
    require(!isInstalled)
    isInstalled = true

    viewStateListener = ViewStateListener(view.id)
    view.addOnAttachStateChangeListener(viewStateListener)
    if (view.isAttachedToWindow) {
      viewStateListener.onViewAttachedToWindow(view)
    }
  }

  /**
   * TODO update kdoc on this param, now includes current screen as well
   * @param retainedRenderings the renderings to be considered hidden after this update. Any
   * associated view state will be retained in the cache, possibly to be restored to [newView]
   * on a succeeding call to his method. Any other cached view state will be dropped.
   *
   * @param oldViewMaybe the view that is being removed, if any, which is expected to be showing
   * a [Named] rendering. If that rendering is
   * [compatible with][com.squareup.workflow1.ui.compatible] a member of
   * [retainedRenderings], its state will be [saved][View.saveHierarchyState].
   *
   * @param newView the view that is about to be displayed, which must be showing a
   * [Named] rendering. If [compatible][com.squareup.workflow1.ui.compatible]
   * view state is found in the cache, it is [restored][View.restoreHierarchyState].
   *
   * @return true if [newView] has been restored.
   */
  public fun update(
    retainedRenderings: Collection<Named<*>>,
    oldViewMaybe: View?,
    newView: View
  ) {
    require(isInstalled)

    val oldFrame = currentFrame
    val newKey = newView.namedKey
    val retainedKeys = retainedRenderings.mapTo(mutableSetOf()) { it.compatibilityKey }
    require(retainedRenderings.size == retainedKeys.size) {
      "Duplicate entries not allowed in $retainedRenderings."
    }
    require(newKey in retainedKeys) {
      "Expected retainedRenderings to include newView's rendering."
    }

    val restoredFrame = hiddenViewStates.remove(newKey)
    currentFrame = if (restoredFrame == null) {
      // This is either a new screen, or the entire activity is being restored, so we need to create
      // a new frame for it. If the activity is being restored, the Android framework will take
      // care of dispatching the restore calls.
      ViewStateFrame(newKey).also {
        it.installOn(newView)

        // Only restore the registry if we're not expecting a call to restoreFromBundle, since the
        // registry can only be restored once.
        // In other words, if inRestored is false, this is the first view this ViewStateCache has
        // seen. Otherwise, this is a navigation.
        if (isRestored) {
          it.restoreStateRegistry()
        }
      }
    } else {
      // We're navigating back to an old screen, so we need to restore it manually.
      restoredFrame.also {
        it.installOn(newView)
        it.restoreStateRegistry()
        it.restoreHierarchyState(newView)
      }
    }

    if (oldViewMaybe != null) {
      // The old view should have been shown by a previous call to update, which should have also
      // set currentFrame, which means oldFrame should never be null.
      requireNotNull(oldFrame) {
        "Expected oldViewMaybe to have been previously passed to update."
      }
      val oldKey = oldViewMaybe.namedKey
      require(oldKey !in hiddenViewStates) {
        "Something's wrong – the old key is already hidden."
      }

      if (oldKey in retainedKeys) {
        // Old view may be returned to later, so we need to save its state.
        // Note that this must be done before destroying the lifecycle.
        oldFrame.performSave(oldViewMaybe)
      }

      // Don't destroy the lifecycle right away, wait until the view is detached (e.g. after the
      // transition has finished).
      oldFrame.destroyOnDetach()
      hiddenViewStates[oldKey] = oldFrame
    }

    pruneKeys(retainedKeys)
  }

  private fun saveToBundle(): Bundle {
    return Bundle().apply {
      currentFrame?.let {
        // First ask the current SavedStateRegistry to save its state providers.
        // We don't need to pass a view in here because the current view will already have its state
        // saved by the regular view tree traversal.
        it.performSave()
        putParcelable(it.key, it)
      }

      hiddenViewStates.forEach { (key, frame) ->
        putParcelable(key, frame)
      }
    }
  }

  /**
   * TODO kdoc
   * Called as soon as the lifecycle has moved to the CREATED state.
   */
  private fun restoreFromBundle(bundle: Bundle?) {
    require(!isRestored)
    isRestored = true

    hiddenViewStates.clear()

    bundle?.keySet()?.forEach { key ->
      val frame = bundle.getParcelable<ViewStateFrame>(key)!!
      if (key == currentFrame?.key) {
        currentFrame!!.loadStateRegistryFrom(frame)
      } else {
        hiddenViewStates[key] = frame
      }
    }

    currentFrame!!.restoreStateRegistry()
  }
}

@WorkflowUiExperimentalApi
private val View.namedKey: String
  get() {
    val rendering = getRendering<Named<*>>()
    return checkNotNull(rendering?.compatibilityKey) {
      "Expected $this to be showing a ${Named::class.java.simpleName}<*> rendering, " +
        "found $rendering"
    }
  }

private fun View.savedStateRegistryOwnerOrNull(): SavedStateRegistryOwner? {
  return ViewTreeSavedStateRegistryOwner.get(this) ?: run {
    var ctx = context
    while (ctx != null) {
      ctx = when (ctx) {
        is SavedStateRegistryOwner -> return ctx
        is ContextWrapper -> ctx.baseContext
        else -> null
      }
    }
    return null
  }
}
