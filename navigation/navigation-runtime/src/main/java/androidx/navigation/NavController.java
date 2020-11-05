/*
 * Copyright (C) 2017 The Android Open Source Project
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

package androidx.navigation;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.OnBackPressedDispatcher;
import androidx.annotation.CallSuper;
import androidx.annotation.IdRes;
import androidx.annotation.NavigationRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.core.app.TaskStackBuilder;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelStore;
import androidx.lifecycle.ViewModelStoreOwner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * NavController manages app navigation within a {@link NavHost}.
 *
 * <p>Apps will generally obtain a controller directly from a host, or by using one of the utility
 * methods on the {@link Navigation} class rather than create a controller directly.</p>
 *
 * <p>Navigation flows and destinations are determined by the
 * {@link NavGraph navigation graph} owned by the controller. These graphs are typically
 * {@link #getNavInflater() inflated} from an Android resource, but, like views, they can also
 * be constructed or combined programmatically or for the case of dynamic navigation structure.
 * (For example, if the navigation structure of the application is determined by live data obtained'
 * from a remote server.)</p>
 */
public class NavController {
    private static final String TAG = "NavController";
    private static final String KEY_NAVIGATOR_STATE =
            "android-support-nav:controller:navigatorState";
    private static final String KEY_NAVIGATOR_STATE_NAMES =
            "android-support-nav:controller:navigatorState:names";
    private static final String KEY_BACK_STACK =
            "android-support-nav:controller:backStack";
    static final String KEY_DEEP_LINK_IDS = "android-support-nav:controller:deepLinkIds";
    static final String KEY_DEEP_LINK_ARGS =
            "android-support-nav:controller:deepLinkArgs";
    static final String KEY_DEEP_LINK_EXTRAS =
            "android-support-nav:controller:deepLinkExtras";
    static final String KEY_DEEP_LINK_HANDLED =
            "android-support-nav:controller:deepLinkHandled";
    /**
     * The {@link Intent} that triggered a deep link to the current destination.
     */
    public static final @NonNull String KEY_DEEP_LINK_INTENT =
            "android-support-nav:controller:deepLinkIntent";

    private final Context mContext;
    private Activity mActivity;
    private NavInflater mInflater;
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    NavGraph mGraph;
    private Bundle mNavigatorStateToRestore;
    private Parcelable[] mBackStackToRestore;
    private boolean mDeepLinkHandled;

    @SuppressWarnings("WeakerAccess") /* synthetic access */
    final Deque<NavBackStackEntry> mBackStack = new ArrayDeque<>();

    private LifecycleOwner mLifecycleOwner;
    private NavControllerViewModel mViewModel;

    private NavigatorProvider mNavigatorProvider = new NavigatorProvider();

    private final CopyOnWriteArrayList<OnDestinationChangedListener>
            mOnDestinationChangedListeners = new CopyOnWriteArrayList<>();

    private final LifecycleObserver mLifecycleObserver = new LifecycleEventObserver() {
        @Override
        public void onStateChanged(@NonNull LifecycleOwner source,
                @NonNull Lifecycle.Event event) {
            if (mGraph != null) {
                for (NavBackStackEntry entry : mBackStack) {
                    entry.handleLifecycleEvent(event);
                }
            }
        }
    };
    private final OnBackPressedCallback mOnBackPressedCallback =
            new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            popBackStack();
        }
    };
    private boolean mEnableOnBackPressedCallback = true;

    /**
     * OnDestinationChangedListener receives a callback when the
     * {@link #getCurrentDestination()} or its arguments change.
     */
    public interface OnDestinationChangedListener {
        /**
         * Callback for when the {@link #getCurrentDestination()} or its arguments change.
         * This navigation may be to a destination that has not been seen before, or one that
         * was previously on the back stack. This method is called after navigation is complete,
         * but associated transitions may still be playing.
         *
         * @param controller the controller that navigated
         * @param destination the new destination
         * @param arguments the arguments passed to the destination
         */
        void onDestinationChanged(@NonNull NavController controller,
                @NonNull NavDestination destination, @Nullable Bundle arguments);
    }

    /**
     * Constructs a new controller for a given {@link Context}. Controllers should not be
     * used outside of their context and retain a hard reference to the context supplied.
     * If you need a global controller, pass {@link Context#getApplicationContext()}.
     *
     * <p>Apps should generally not construct controllers, instead obtain a relevant controller
     * directly from a navigation host via {@link NavHost#getNavController()} or by using one of
     * the utility methods on the {@link Navigation} class.</p>
     *
     * <p>Note that controllers that are not constructed with an {@link Activity} context
     * (or a wrapped activity context) will only be able to navigate to
     * {@link android.content.Intent#FLAG_ACTIVITY_NEW_TASK new tasks} or
     * {@link android.content.Intent#FLAG_ACTIVITY_NEW_DOCUMENT new document tasks} when
     * navigating to new activities.</p>
     *
     * @param context context for this controller
     */
    public NavController(@NonNull Context context) {
        mContext = context;
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                mActivity = (Activity) context;
                break;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        mNavigatorProvider.addNavigator(new NavGraphNavigator(mNavigatorProvider));
        mNavigatorProvider.addNavigator(new ActivityNavigator(mContext));
    }

    /**
     * Retrieve the current back stack.
     *
     * @return The current back stack.
     * @hide
     */
    @NonNull
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public Deque<NavBackStackEntry> getBackStack() {
        return mBackStack;
    }

    @NonNull
    Context getContext() {
        return mContext;
    }

    /**
     * Retrieve the NavController's {@link NavigatorProvider}. All {@link Navigator Navigators} used
     * to construct the {@link NavGraph navigation graph} for this nav controller should be added
     * to this navigator provider before the graph is constructed.
     * <p>
     * Generally, the Navigators are set for you by the {@link NavHost} hosting this NavController
     * and you do not need to manually interact with the navigator provider.
     * </p>
     * @return The {@link NavigatorProvider} used by this NavController.
     */
    @NonNull
    public NavigatorProvider getNavigatorProvider() {
        return mNavigatorProvider;
    }

    /**
     * Sets the {@link NavigatorProvider navigator provider} to the specified provider. This can
     * only be called before the graph is set via {@code setGraph()}.
     *
     * @param navigatorProvider {@link NavigatorProvider} to set
     * @throws IllegalStateException If this is called after {@code setGraph()}
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public void setNavigatorProvider(@NonNull NavigatorProvider navigatorProvider) {
        if (!mBackStack.isEmpty()) {
            throw new IllegalStateException("NavigatorProvider must be set before setGraph call");
        }
        mNavigatorProvider = navigatorProvider;
    }

    /**
     * Adds an {@link OnDestinationChangedListener} to this controller to receive a callback
     * whenever the {@link #getCurrentDestination()} or its arguments change.
     *
     * <p>The current destination, if any, will be immediately sent to your listener.</p>
     *
     * @param listener the listener to receive events
     */
    public void addOnDestinationChangedListener(@NonNull OnDestinationChangedListener listener) {
        // Inform the new listener of our current state, if any
        if (!mBackStack.isEmpty()) {
            NavBackStackEntry backStackEntry = mBackStack.peekLast();
            listener.onDestinationChanged(this, backStackEntry.getDestination(),
                    backStackEntry.getArguments());
        }
        mOnDestinationChangedListeners.add(listener);
    }

    /**
     * Removes an {@link OnDestinationChangedListener} from this controller.
     * It will no longer receive callbacks.
     *
     * @param listener the listener to remove
     */
    public void removeOnDestinationChangedListener(
            @NonNull OnDestinationChangedListener listener) {
        mOnDestinationChangedListeners.remove(listener);
    }

    /**
     * Attempts to pop the controller's back stack. Analogous to when the user presses
     * the system {@link android.view.KeyEvent#KEYCODE_BACK Back} button when the associated
     * navigation host has focus.
     *
     * @return true if the stack was popped and the user has been navigated to another
     * destination, false otherwise
     */
    public boolean popBackStack() {
        if (mBackStack.isEmpty()) {
            // Nothing to pop if the back stack is empty
            return false;
        }
        // Pop just the current destination off the stack
        return popBackStack(getCurrentDestination().getId(), true);
    }

    /**
     * Attempts to pop the controller's back stack back to a specific destination.
     *
     * @param destinationId The topmost destination to retain
     * @param inclusive Whether the given destination should also be popped.
     *
     * @return true if the stack was popped at least once and the user has been navigated to
     * another destination, false otherwise
     */
    public boolean popBackStack(@IdRes int destinationId, boolean inclusive) {
        boolean popped = popBackStackInternal(destinationId, inclusive);
        // Only return true if the pop succeeded and we've dispatched
        // the change to a new destination
        return popped && dispatchOnDestinationChanged();
    }

    /**
     * Attempts to pop the controller's back stack back to a specific destination. This does
     * <strong>not</strong> handle calling {@link #dispatchOnDestinationChanged()}
     *
     * @param destinationId The topmost destination to retain
     * @param inclusive Whether the given destination should also be popped.
     *
     * @return true if the stack was popped at least once, false otherwise
     */
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    boolean popBackStackInternal(@IdRes int destinationId, boolean inclusive) {
        if (mBackStack.isEmpty()) {
            // Nothing to pop if the back stack is empty
            return false;
        }
        ArrayList<Navigator<?>> popOperations = new ArrayList<>();
        Iterator<NavBackStackEntry> iterator = mBackStack.descendingIterator();
        boolean foundDestination = false;
        while (iterator.hasNext()) {
            NavDestination destination = iterator.next().getDestination();
            Navigator<?> navigator = mNavigatorProvider.getNavigator(
                    destination.getNavigatorName());
            if (inclusive || destination.getId() != destinationId) {
                popOperations.add(navigator);
            }
            if (destination.getId() == destinationId) {
                foundDestination = true;
                break;
            }
        }
        if (!foundDestination) {
            // We were passed a destinationId that doesn't exist on our back stack.
            // Better to ignore the popBackStack than accidentally popping the entire stack
            String destinationName = NavDestination.getDisplayName(mContext, destinationId);
            Log.i(TAG, "Ignoring popBackStack to destination " + destinationName
                    + " as it was not found on the current back stack");
            return false;
        }
        boolean popped = false;
        for (Navigator<?> navigator : popOperations) {
            if (navigator.popBackStack()) {
                NavBackStackEntry entry = mBackStack.removeLast();
                entry.setMaxLifecycle(Lifecycle.State.DESTROYED);
                if (mViewModel != null) {
                    mViewModel.clear(entry.mId);
                }
                popped = true;
            } else {
                // The pop did not complete successfully, so stop immediately
                break;
            }
        }
        updateOnBackPressedCallbackEnabled();
        return popped;
    }

    /**
     * Attempts to navigate up in the navigation hierarchy. Suitable for when the
     * user presses the "Up" button marked with a left (or start)-facing arrow in the upper left
     * (or starting) corner of the app UI.
     *
     * <p>The intended behavior of Up differs from {@link #popBackStack() Back} when the user
     * did not reach the current destination from the application's own task. e.g. if the user
     * is viewing a document or link in the current app in an activity hosted on another app's
     * task where the user clicked the link. In this case the current activity (determined by the
     * context used to create this NavController) will be {@link Activity#finish() finished} and
     * the user will be taken to an appropriate destination in this app on its own task.</p>
     *
     * @return true if navigation was successful, false otherwise
     */
    public boolean navigateUp() {
        if (getDestinationCountOnBackStack() == 1) {
            // If there's only one entry, then we've deep linked into a specific destination
            // on another task so we need to find the parent and start our task from there
            NavDestination currentDestination = getCurrentDestination();
            int destId = currentDestination.getId();
            NavGraph parent = currentDestination.getParent();
            while (parent != null) {
                if (parent.getStartDestination() != destId) {
                    final Bundle args = new Bundle();

                    if (mActivity != null && mActivity.getIntent() != null) {
                        final Uri data = mActivity.getIntent().getData();

                        // We were started via a URI intent.
                        if (data != null) {
                            // Include the original deep link Intent so the Destinations can
                            // synthetically generate additional arguments as necessary.
                            args.putParcelable(KEY_DEEP_LINK_INTENT, mActivity.getIntent());
                            NavDestination.DeepLinkMatch matchingDeepLink = mGraph.matchDeepLink(
                                    new NavDeepLinkRequest(mActivity.getIntent()));
                            if (matchingDeepLink != null) {
                                args.putAll(matchingDeepLink.getMatchingArgs());
                            }
                        }
                    }

                    TaskStackBuilder parentIntents = new NavDeepLinkBuilder(this)
                            .setDestination(parent.getId())
                            .setArguments(args)
                            .createTaskStackBuilder();

                    parentIntents.startActivities();
                    if (mActivity != null) {
                        mActivity.finish();
                    }
                    return true;
                }
                destId = parent.getId();
                parent = parent.getParent();
            }
            // We're already at the startDestination of the graph so there's no 'Up' to go to
            return false;
        } else {
            return popBackStack();
        }
    }

    /**
     * Gets the number of non-NavGraph destinations on the back stack
     */
    private int getDestinationCountOnBackStack() {
        int count = 0;
        for (NavBackStackEntry entry : mBackStack) {
            if (!(entry.getDestination() instanceof NavGraph)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Dispatch changes to all OnDestinationChangedListeners.
     * <p>
     * If the back stack is empty, no events get dispatched.
     *
     * @return If changes were dispatched.
     */
    private boolean dispatchOnDestinationChanged() {
        // We never want to leave NavGraphs on the top of the stack
        //noinspection StatementWithEmptyBody
        while (!mBackStack.isEmpty()
                && mBackStack.peekLast().getDestination() instanceof NavGraph
                && popBackStackInternal(mBackStack.peekLast().getDestination().getId(), true)) {
            // Keep popping
        }
        if (!mBackStack.isEmpty()) {
            // First determine what the current resumed destination is and, if and only if
            // the current resumed destination is a FloatingWindow, what destination is
            // underneath it that must remain started.
            NavDestination nextResumed = mBackStack.peekLast().getDestination();
            NavDestination nextStarted = null;
            if (nextResumed instanceof FloatingWindow) {
                // Find the next destination in the back stack as that destination
                // should still be STARTED when the FloatingWindow destination is above it.
                Iterator<NavBackStackEntry> iterator = mBackStack.descendingIterator();
                while (iterator.hasNext()) {
                    NavDestination destination = iterator.next().getDestination();
                    if (!(destination instanceof NavGraph)
                            && !(destination instanceof FloatingWindow)) {
                        nextStarted = destination;
                        break;
                    }
                }
            }
            // First iterate downward through the stack, applying downward Lifecycle
            // transitions and capturing any upward Lifecycle transitions to apply afterwards.
            // This ensures proper nesting where parent navigation graphs are started before
            // their children and stopped only after their children are stopped.
            HashMap<NavBackStackEntry, Lifecycle.State> upwardStateTransitions = new HashMap<>();
            Iterator<NavBackStackEntry> iterator = mBackStack.descendingIterator();
            while (iterator.hasNext()) {
                NavBackStackEntry entry = iterator.next();
                Lifecycle.State currentMaxLifecycle = entry.getMaxLifecycle();
                NavDestination destination = entry.getDestination();
                if (nextResumed != null && destination.getId() == nextResumed.getId()) {
                    // Upward Lifecycle transitions need to be done afterwards so that
                    // the parent navigation graph is resumed before their children
                    if (currentMaxLifecycle != Lifecycle.State.RESUMED) {
                        upwardStateTransitions.put(entry, Lifecycle.State.RESUMED);
                    }
                    nextResumed = nextResumed.getParent();
                } else if (nextStarted != null && destination.getId() == nextStarted.getId()) {
                    if (currentMaxLifecycle == Lifecycle.State.RESUMED) {
                        // Downward transitions should be done immediately so children are
                        // paused before their parent navigation graphs
                        entry.setMaxLifecycle(Lifecycle.State.STARTED);
                    } else if (currentMaxLifecycle != Lifecycle.State.STARTED) {
                        // Upward Lifecycle transitions need to be done afterwards so that
                        // the parent navigation graph is started before their children
                        upwardStateTransitions.put(entry, Lifecycle.State.STARTED);
                    }
                    nextStarted = nextStarted.getParent();
                } else {
                    entry.setMaxLifecycle(Lifecycle.State.CREATED);
                }
            }
            // Apply all upward Lifecycle transitions by iterating through the stack again,
            // this time applying the new lifecycle to the parent navigation graphs first
            iterator = mBackStack.iterator();
            while (iterator.hasNext()) {
                NavBackStackEntry entry = iterator.next();
                Lifecycle.State newState = upwardStateTransitions.get(entry);
                if (newState != null) {
                    entry.setMaxLifecycle(newState);
                } else {
                    // Ensure the state is up to date
                    entry.updateState();
                }
            }

            // Now call all registered OnDestinationChangedListener instances
            NavBackStackEntry backStackEntry = mBackStack.peekLast();
            for (OnDestinationChangedListener listener :
                    mOnDestinationChangedListeners) {
                listener.onDestinationChanged(this, backStackEntry.getDestination(),
                        backStackEntry.getArguments());
            }
            return true;
        }
        return false;
    }

    /**
     * Returns the {@link NavInflater inflater} for this controller.
     *
     * @return inflater for loading navigation resources
     */
    @NonNull
    public NavInflater getNavInflater() {
        if (mInflater == null) {
            mInflater = new NavInflater(mContext, mNavigatorProvider);
        }
        return mInflater;
    }

    /**
     * Sets the {@link NavGraph navigation graph} to the specified resource.
     * Any current navigation graph data (including back stack) will be replaced.
     *
     * <p>The inflated graph can be retrieved via {@link #getGraph()}.</p>
     *
     * @param graphResId resource id of the navigation graph to inflate
     *
     * @see #getNavInflater()
     * @see #setGraph(NavGraph)
     * @see #getGraph
     */
    @CallSuper
    public void setGraph(@NavigationRes int graphResId) {
        setGraph(graphResId, null);
    }

    /**
     * Sets the {@link NavGraph navigation graph} to the specified resource.
     * Any current navigation graph data (including back stack) will be replaced.
     *
     * <p>The inflated graph can be retrieved via {@link #getGraph()}.</p>
     *
     * @param graphResId resource id of the navigation graph to inflate
     * @param startDestinationArgs arguments to send to the start destination of the graph
     *
     * @see #getNavInflater()
     * @see #setGraph(NavGraph, Bundle)
     * @see #getGraph
     */
    @CallSuper
    public void setGraph(@NavigationRes int graphResId, @Nullable Bundle startDestinationArgs) {
        setGraph(getNavInflater().inflate(graphResId), startDestinationArgs);
    }

    /**
     * Sets the {@link NavGraph navigation graph} to the specified graph.
     * Any current navigation graph data (including back stack) will be replaced.
     *
     * <p>The graph can be retrieved later via {@link #getGraph()}.</p>
     *
     * @param graph graph to set
     * @see #setGraph(int)
     * @see #getGraph
     */
    @CallSuper
    public void setGraph(@NonNull NavGraph graph) {
        setGraph(graph, null);
    }

    /**
     * Sets the {@link NavGraph navigation graph} to the specified graph.
     * Any current navigation graph data (including back stack) will be replaced.
     *
     * <p>The graph can be retrieved later via {@link #getGraph()}.</p>
     *
     * @param graph graph to set
     * @see #setGraph(int, Bundle)
     * @see #getGraph
     */
    @CallSuper
    public void setGraph(@NonNull NavGraph graph, @Nullable Bundle startDestinationArgs) {
        if (mGraph != null) {
            // Pop everything from the old graph off the back stack
            popBackStackInternal(mGraph.getId(), true);
        }
        mGraph = graph;
        onGraphCreated(startDestinationArgs);
    }

    private void onGraphCreated(@Nullable Bundle startDestinationArgs) {
        if (mNavigatorStateToRestore != null) {
            ArrayList<String> navigatorNames = mNavigatorStateToRestore.getStringArrayList(
                    KEY_NAVIGATOR_STATE_NAMES);
            if (navigatorNames != null) {
                for (String name : navigatorNames) {
                    Navigator<?> navigator = mNavigatorProvider.getNavigator(name);
                    Bundle bundle = mNavigatorStateToRestore.getBundle(name);
                    if (bundle != null) {
                        navigator.onRestoreState(bundle);
                    }
                }
            }
        }
        if (mBackStackToRestore != null) {
            for (Parcelable parcelable : mBackStackToRestore) {
                NavBackStackEntryState state = (NavBackStackEntryState) parcelable;
                NavDestination node = findDestination(state.getDestinationId());
                if (node == null) {
                    final String dest = NavDestination.getDisplayName(mContext,
                            state.getDestinationId());
                    throw new IllegalStateException("Restoring the Navigation back stack failed:"
                            + " destination " + dest
                            + " cannot be found from the current destination "
                            + getCurrentDestination());
                }
                Bundle args = state.getArgs();
                if (args != null) {
                    args.setClassLoader(mContext.getClassLoader());
                }
                NavBackStackEntry entry = new NavBackStackEntry(mContext, node, args,
                        mLifecycleOwner, mViewModel,
                        state.getUUID(), state.getSavedState());
                mBackStack.add(entry);
            }
            updateOnBackPressedCallbackEnabled();
            mBackStackToRestore = null;
        }
        if (mGraph != null && mBackStack.isEmpty()) {
            boolean deepLinked = !mDeepLinkHandled && mActivity != null
                    && handleDeepLink(mActivity.getIntent());
            if (!deepLinked) {
                // Navigate to the first destination in the graph
                // if we haven't deep linked to a destination
                navigate(mGraph, startDestinationArgs, null, null);
            }
        } else {
            dispatchOnDestinationChanged();
        }
    }

    /**
     * Checks the given Intent for a Navigation deep link and navigates to the deep link if present.
     * This is called automatically for you the first time you set the graph if you've passed in an
     * {@link Activity} as the context when constructing this NavController, but should be manually
     * called if your Activity receives new Intents in {@link Activity#onNewIntent(Intent)}.
     * <p>
     * The types of Intents that are supported include:
     * <ul>
     *     <ol>Intents created by {@link NavDeepLinkBuilder} or
     *     {@link #createDeepLink()}. This assumes that the current graph shares
     *     the same hierarchy to get to the deep linked destination as when the deep link was
     *     constructed.</ol>
     *     <ol>Intents that include a {@link Intent#getData() data Uri}. This Uri will be checked
     *     against the Uri patterns in the {@link NavDeepLink NavDeepLinks} added via
     *     {@link NavDestination#addDeepLink(NavDeepLink)}.</ol>
     * </ul>
     * <p>The {@link #getGraph() navigation graph} should be set before calling this method.</p>
     * @param intent The Intent that may contain a valid deep link
     * @return True if the navigation controller found a valid deep link and navigated to it.
     * @see NavDestination#addDeepLink(NavDeepLink)
     */
    public boolean handleDeepLink(@Nullable Intent intent) {
        if (intent == null) {
            return false;
        }
        Bundle extras = intent.getExtras();
        int[] deepLink = extras != null ? extras.getIntArray(KEY_DEEP_LINK_IDS) : null;
        ArrayList<Bundle> deepLinkArgs = extras != null
                ? extras.getParcelableArrayList(KEY_DEEP_LINK_ARGS)
                : null;
        Bundle globalArgs = new Bundle();
        Bundle deepLinkExtras = extras != null ? extras.getBundle(KEY_DEEP_LINK_EXTRAS) : null;
        if (deepLinkExtras != null) {
            globalArgs.putAll(deepLinkExtras);
        }
        if ((deepLink == null || deepLink.length == 0) && intent.getData() != null) {
            NavDestination.DeepLinkMatch matchingDeepLink =
                    mGraph.matchDeepLink(new NavDeepLinkRequest(intent));
            if (matchingDeepLink != null) {
                deepLink = matchingDeepLink.getDestination().buildDeepLinkIds();
                deepLinkArgs = null;
                globalArgs.putAll(matchingDeepLink.getMatchingArgs());
            }
        }
        if (deepLink == null || deepLink.length == 0) {
            return false;
        }
        String invalidDestinationDisplayName =
                findInvalidDestinationDisplayNameInDeepLink(deepLink);
        if (invalidDestinationDisplayName != null) {
            Log.i(TAG, "Could not find destination " + invalidDestinationDisplayName
                    + " in the navigation graph, ignoring the deep link from " + intent);
            return false;
        }
        globalArgs.putParcelable(KEY_DEEP_LINK_INTENT, intent);
        Bundle[] args = new Bundle[deepLink.length];
        for (int index = 0; index < args.length; index++) {
            Bundle arguments = new Bundle();
            arguments.putAll(globalArgs);
            if (deepLinkArgs != null) {
                Bundle deepLinkArguments = deepLinkArgs.get(index);
                if (deepLinkArguments != null) {
                    arguments.putAll(deepLinkArguments);
                }
            }
            args[index] = arguments;
        }
        int flags = intent.getFlags();
        if ((flags & Intent.FLAG_ACTIVITY_NEW_TASK) != 0
                && (flags & Intent.FLAG_ACTIVITY_CLEAR_TASK) == 0) {
            // Someone called us with NEW_TASK, but we don't know what state our whole
            // task stack is in, so we need to manually restart the whole stack to
            // ensure we're in a predictably good state.
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
            TaskStackBuilder taskStackBuilder = TaskStackBuilder
                    .create(mContext)
                    .addNextIntentWithParentStack(intent);
            taskStackBuilder.startActivities();
            if (mActivity != null) {
                mActivity.finish();
                // Disable second animation in case where the Activity is created twice.
                mActivity.overridePendingTransition(0, 0);
            }
            return true;
        }
        if ((flags & Intent.FLAG_ACTIVITY_NEW_TASK) != 0) {
            // Start with a cleared task starting at our root when we're on our own task
            if (!mBackStack.isEmpty()) {
                popBackStackInternal(mGraph.getId(), true);
            }
            int index = 0;
            while (index < deepLink.length) {
                int destinationId = deepLink[index];
                Bundle arguments = args[index++];
                NavDestination node = findDestination(destinationId);
                if (node == null) {
                    final String dest = NavDestination.getDisplayName(mContext, destinationId);
                    throw new IllegalStateException("Deep Linking failed:"
                            + " destination " + dest
                            + " cannot be found from the current destination "
                            + getCurrentDestination());
                }
                navigate(node, arguments,
                        new NavOptions.Builder().setEnterAnim(0).setExitAnim(0).build(), null);
            }
            return true;
        }
        // Assume we're on another apps' task and only start the final destination
        NavGraph graph = mGraph;
        for (int i = 0; i < deepLink.length; i++) {
            int destinationId = deepLink[i];
            Bundle arguments = args[i];
            NavDestination node = i == 0 ? mGraph : graph.findNode(destinationId);
            if (node == null) {
                final String dest = NavDestination.getDisplayName(mContext, destinationId);
                throw new IllegalStateException("Deep Linking failed:"
                        + " destination " + dest
                        + " cannot be found in graph " + graph);
            }
            if (i != deepLink.length - 1) {
                // We're not at the final NavDestination yet, so keep going through the chain
                if (node instanceof NavGraph) {
                    graph = (NavGraph) node;
                    // Automatically go down the navigation graph when
                    // the start destination is also a NavGraph
                    while (graph.findNode(graph.getStartDestination()) instanceof NavGraph) {
                        graph = (NavGraph) graph.findNode(graph.getStartDestination());
                    }
                }
            } else {
                // Navigate to the last NavDestination, clearing any existing destinations
                navigate(node, arguments, new NavOptions.Builder()
                        .setPopUpTo(mGraph.getId(), true)
                        .setEnterAnim(0).setExitAnim(0).build(), null);
            }
        }
        mDeepLinkHandled = true;
        return true;
    }

    /**
     * Looks through the deep link for invalid destinations, returning the display name of
     * any invalid destinations in the deep link array.
     *
     * @param deepLink array of deep link IDs that are expected to match the graph
     * @return The display name of the first destination not found in the graph or null if
     * all destinations were found in the graph.
     */
    @Nullable
    private String findInvalidDestinationDisplayNameInDeepLink(@NonNull int[] deepLink) {
        NavGraph graph = mGraph;
        for (int i = 0; i < deepLink.length; i++) {
            int destinationId = deepLink[i];
            NavDestination node = i == 0
                    ? (mGraph.getId() == destinationId ? mGraph : null)
                    : graph.findNode(destinationId);
            if (node == null) {
                return NavDestination.getDisplayName(mContext, destinationId);
            }
            if (i != deepLink.length - 1) {
                // We're not at the final NavDestination yet, so keep going through the chain
                if (node instanceof NavGraph) {
                    graph = (NavGraph) node;
                    // Automatically go down the navigation graph when
                    // the start destination is also a NavGraph
                    while (graph.findNode(graph.getStartDestination()) instanceof NavGraph) {
                        graph = (NavGraph) graph.findNode(graph.getStartDestination());
                    }
                }
            }
        }
        // We found every destination in the deepLink array, yay!
        return null;
    }

    /**
     * Gets the topmost navigation graph associated with this NavController.
     *
     * @see #setGraph(int)
     * @see #setGraph(NavGraph)
     * @throws IllegalStateException if called before <code>setGraph()</code>.
     */
    @NonNull
    public NavGraph getGraph() {
        if (mGraph == null) {
            throw new IllegalStateException("You must call setGraph() before calling getGraph()");
        }
        return mGraph;
    }

    /**
     * Gets the current destination.
     */
    @Nullable
    public NavDestination getCurrentDestination() {
        NavBackStackEntry entry = getCurrentBackStackEntry();
        return entry != null ? entry.getDestination() : null;
    }

    @SuppressWarnings("WeakerAccess") /* synthetic access */
    NavDestination findDestination(@IdRes int destinationId) {
        if (mGraph == null) {
            return null;
        }
        if (mGraph.getId() == destinationId) {
            return mGraph;
        }
        NavDestination currentNode = mBackStack.isEmpty()
                ? mGraph
                : mBackStack.getLast().getDestination();
        NavGraph currentGraph = currentNode instanceof NavGraph
                ? (NavGraph) currentNode
                : currentNode.getParent();
        return currentGraph.findNode(destinationId);
    }

    /**
     * Navigate to a destination from the current navigation graph. This supports both navigating
     * via an {@link NavDestination#getAction(int) action} and directly navigating to a destination.
     *
     * @param resId an {@link NavDestination#getAction(int) action} id or a destination id to
     *              navigate to
     */
    public void navigate(@IdRes int resId) {
        navigate(resId, null);
    }

    /**
     * Navigate to a destination from the current navigation graph. This supports both navigating
     * via an {@link NavDestination#getAction(int) action} and directly navigating to a destination.
     *
     * @param resId an {@link NavDestination#getAction(int) action} id or a destination id to
     *              navigate to
     * @param args arguments to pass to the destination
     */
    public void navigate(@IdRes int resId, @Nullable Bundle args) {
        navigate(resId, args, null);
    }

    /**
     * Navigate to a destination from the current navigation graph. This supports both navigating
     * via an {@link NavDestination#getAction(int) action} and directly navigating to a destination.
     *
     * @param resId an {@link NavDestination#getAction(int) action} id or a destination id to
     *              navigate to
     * @param args arguments to pass to the destination
     * @param navOptions special options for this navigation operation
     */
    public void navigate(@IdRes int resId, @Nullable Bundle args,
            @Nullable NavOptions navOptions) {
        navigate(resId, args, navOptions, null);
    }

    /**
     * Navigate to a destination from the current navigation graph. This supports both navigating
     * via an {@link NavDestination#getAction(int) action} and directly navigating to a destination.
     *
     * @param resId an {@link NavDestination#getAction(int) action} id or a destination id to
     *              navigate to
     * @param args arguments to pass to the destination
     * @param navOptions special options for this navigation operation
     * @param navigatorExtras extras to pass to the Navigator
     */
    @SuppressWarnings("deprecation")
    public void navigate(@IdRes int resId, @Nullable Bundle args, @Nullable NavOptions navOptions,
            @Nullable Navigator.Extras navigatorExtras) {
        NavDestination currentNode = mBackStack.isEmpty()
                ? mGraph
                : mBackStack.getLast().getDestination();
        if (currentNode == null) {
            throw new IllegalStateException("no current navigation node");
        }
        @IdRes int destId = resId;
        final NavAction navAction = currentNode.getAction(resId);
        Bundle combinedArgs = null;
        if (navAction != null) {
            if (navOptions == null) {
                navOptions = navAction.getNavOptions();
            }
            destId = navAction.getDestinationId();
            Bundle navActionArgs = navAction.getDefaultArguments();
            if (navActionArgs != null) {
                combinedArgs = new Bundle();
                combinedArgs.putAll(navActionArgs);
            }
        }

        if (args != null) {
            if (combinedArgs == null) {
                combinedArgs = new Bundle();
            }
            combinedArgs.putAll(args);
        }

        if (destId == 0 && navOptions != null && navOptions.getPopUpTo() != -1) {
            popBackStack(navOptions.getPopUpTo(), navOptions.isPopUpToInclusive());
            return;
        }

        if (destId == 0) {
            throw new IllegalArgumentException("Destination id == 0 can only be used"
                    + " in conjunction with a valid navOptions.popUpTo");
        }

        NavDestination node = findDestination(destId);
        if (node == null) {
            final String dest = NavDestination.getDisplayName(mContext, destId);
            if (navAction != null) {
                throw new IllegalArgumentException("Navigation destination " + dest
                        + " referenced from action "
                        + NavDestination.getDisplayName(mContext, resId)
                        + " cannot be found from the current destination " + currentNode);
            } else {
                throw new IllegalArgumentException("Navigation action/destination " + dest
                        + " cannot be found from the current destination " + currentNode);
            }
        }
        navigate(node, combinedArgs, navOptions, navigatorExtras);
    }

    /**
     * Navigate to a destination via the given deep link {@link Uri}.
     * {@link NavDestination#hasDeepLink(Uri)} should be called on
     * {@link #getGraph() the navigation graph} prior to calling this method to check if the deep
     * link is valid. If an invalid deep link is given, an {@link IllegalArgumentException} will be
     * thrown.
     *
     * @param deepLink deepLink to the destination reachable from the current NavGraph
     * @see #navigate(NavDeepLinkRequest)
     */
    public void navigate(@NonNull Uri deepLink) {
        navigate(new NavDeepLinkRequest(deepLink, null, null));
    }

    /**
     * Navigate to a destination via the given deep link {@link Uri}.
     * {@link NavDestination#hasDeepLink(Uri)} should be called on
     * {@link #getGraph() the navigation graph} prior to calling this method to check if the deep
     * link is valid. If an invalid deep link is given, an {@link IllegalArgumentException} will be
     * thrown.
     *
     * @param deepLink deepLink to the destination reachable from the current NavGraph
     * @param navOptions special options for this navigation operation
     * @see #navigate(NavDeepLinkRequest, NavOptions)
     */
    public void navigate(@NonNull Uri deepLink, @Nullable NavOptions navOptions) {
        navigate(new NavDeepLinkRequest(deepLink, null, null), navOptions);
    }

    /**
     * Navigate to a destination via the given deep link {@link Uri}.
     * {@link NavDestination#hasDeepLink(Uri)} should be called on
     * {@link #getGraph() the navigation graph} prior to calling this method to check if the deep
     * link is valid. If an invalid deep link is given, an {@link IllegalArgumentException} will be
     * thrown.
     *
     * @param deepLink deepLink to the destination reachable from the current NavGraph
     * @param navOptions special options for this navigation operation
     * @param navigatorExtras extras to pass to the Navigator
     * @see #navigate(NavDeepLinkRequest, NavOptions, Navigator.Extras)
     */
    public void navigate(@NonNull Uri deepLink, @Nullable NavOptions navOptions,
            @Nullable Navigator.Extras navigatorExtras) {
        navigate(new NavDeepLinkRequest(deepLink, null, null), navOptions, navigatorExtras);
    }

    /**
     * Navigate to a destination via the given {@link NavDeepLinkRequest}.
     * {@link NavDestination#hasDeepLink(NavDeepLinkRequest)} should be called on
     * {@link #getGraph() the navigation graph} prior to calling this method to check if the deep
     * link is valid. If an invalid deep link is given, an {@link IllegalArgumentException} will be
     * thrown.
     *
     * @param request deepLinkRequest to the destination reachable from the current NavGraph
     */
    public void navigate(@NonNull NavDeepLinkRequest request) {
        navigate(request, null);
    }

    /**
     * Navigate to a destination via the given {@link NavDeepLinkRequest}.
     * {@link NavDestination#hasDeepLink(NavDeepLinkRequest)} should be called on
     * {@link #getGraph() the navigation graph} prior to calling this method to check if the deep
     * link is valid. If an invalid deep link is given, an {@link IllegalArgumentException} will be
     * thrown.
     *
     * @param request deepLinkRequest to the destination reachable from the current NavGraph
     * @param navOptions special options for this navigation operation
     */
    public void navigate(@NonNull NavDeepLinkRequest request, @Nullable NavOptions navOptions) {
        navigate(request, navOptions, null);
    }

    /**
     * Navigate to a destination via the given {@link NavDeepLinkRequest}.
     * {@link NavDestination#hasDeepLink(NavDeepLinkRequest)} should be called on
     * {@link #getGraph() the navigation graph} prior to calling this method to check if the deep
     * link is valid. If an invalid deep link is given, an {@link IllegalArgumentException} will be
     * thrown.
     *
     * @param request deepLinkRequest to the destination reachable from the current NavGraph
     * @param navOptions special options for this navigation operation
     * @param navigatorExtras extras to pass to the Navigator
     */
    public void navigate(@NonNull NavDeepLinkRequest request, @Nullable NavOptions navOptions,
            @Nullable Navigator.Extras navigatorExtras) {
        NavDestination.DeepLinkMatch deepLinkMatch =
                mGraph.matchDeepLink(request);
        if (deepLinkMatch != null) {
            Bundle args = deepLinkMatch.getMatchingArgs();
            NavDestination node = deepLinkMatch.getDestination();
            navigate(node, args, navOptions, navigatorExtras);
        } else {
            throw new IllegalArgumentException("Navigation destination that matches request "
                    + request + " cannot be found in the navigation graph " + mGraph);
        }
    }

    private void navigate(@NonNull NavDestination node, @Nullable Bundle args,
            @Nullable NavOptions navOptions, @Nullable Navigator.Extras navigatorExtras) {
        boolean popped = false;
        boolean launchSingleTop = false;
        if (navOptions != null) {
            if (navOptions.getPopUpTo() != -1) {
                popped = popBackStackInternal(navOptions.getPopUpTo(),
                        navOptions.isPopUpToInclusive());
            }
        }
        Navigator<NavDestination> navigator = mNavigatorProvider.getNavigator(
                node.getNavigatorName());
        Bundle finalArgs = node.addInDefaultArgs(args);
        NavDestination newDest = navigator.navigate(node, finalArgs,
                navOptions, navigatorExtras);
        if (newDest != null) {
            if (!(newDest instanceof FloatingWindow)) {
                // We've successfully navigating to the new destination, which means
                // we should pop any FloatingWindow destination off the back stack
                // before updating the back stack with our new destination
                //noinspection StatementWithEmptyBody
                while (!mBackStack.isEmpty()
                        && mBackStack.peekLast().getDestination() instanceof FloatingWindow
                        && popBackStackInternal(
                                mBackStack.peekLast().getDestination().getId(), true)) {
                    // Keep popping
                }
            }
            // The mGraph should always be on the back stack after you navigate()
            if (mBackStack.isEmpty()) {
                NavBackStackEntry entry = new NavBackStackEntry(mContext, mGraph, finalArgs,
                        mLifecycleOwner, mViewModel);
                mBackStack.add(entry);
            }
            // Now collect the set of all intermediate NavGraphs that need to be put onto
            // the back stack
            ArrayDeque<NavBackStackEntry> hierarchy = new ArrayDeque<>();
            NavDestination destination = newDest;
            while (destination != null && findDestination(destination.getId()) == null) {
                NavGraph parent = destination.getParent();
                if (parent != null) {
                    NavBackStackEntry entry = new NavBackStackEntry(mContext, parent, finalArgs,
                            mLifecycleOwner, mViewModel);
                    hierarchy.addFirst(entry);
                }
                destination = parent;
            }
            NavDestination overlappingDestination = hierarchy.isEmpty()
                    ? newDest
                    : hierarchy.getLast().getDestination();
            // Pop any orphaned navigation graphs that don't connect to the new destinations
            //noinspection StatementWithEmptyBody
            while (!mBackStack.isEmpty()
                    && mBackStack.getLast().getDestination() instanceof NavGraph
                    && ((NavGraph) mBackStack.getLast().getDestination()).findNode(
                            overlappingDestination.getId(), false) == null
                    && popBackStackInternal(mBackStack.getLast().getDestination().getId(), true)) {
                // Keep popping
            }
            mBackStack.addAll(hierarchy);
            // And finally, add the new destination with its default args
            NavBackStackEntry newBackStackEntry = new NavBackStackEntry(mContext, newDest,
                    newDest.addInDefaultArgs(finalArgs), mLifecycleOwner, mViewModel);
            mBackStack.add(newBackStackEntry);
        } else if (navOptions != null && navOptions.shouldLaunchSingleTop()) {
            launchSingleTop = true;
            NavBackStackEntry singleTopBackStackEntry = mBackStack.peekLast();
            if (singleTopBackStackEntry != null) {
                singleTopBackStackEntry.replaceArguments(args);
            }
        }
        updateOnBackPressedCallbackEnabled();
        if (popped || newDest != null || launchSingleTop) {
            dispatchOnDestinationChanged();
        }
    }

    /**
     * Navigate via the given {@link NavDirections}
     *
     * @param directions directions that describe this navigation operation
     */
    public void navigate(@NonNull NavDirections directions) {
        navigate(directions.getActionId(), directions.getArguments());
    }

    /**
     * Navigate via the given {@link NavDirections}
     *
     * @param directions directions that describe this navigation operation
     * @param navOptions special options for this navigation operation
     */
    public void navigate(@NonNull NavDirections directions, @Nullable NavOptions navOptions) {
        navigate(directions.getActionId(), directions.getArguments(), navOptions);
    }

    /**
     * Navigate via the given {@link NavDirections}
     *
     * @param directions directions that describe this navigation operation
     * @param navigatorExtras extras to pass to the {@link Navigator}
     */
    public void navigate(@NonNull NavDirections directions,
            @NonNull Navigator.Extras navigatorExtras) {
        navigate(directions.getActionId(), directions.getArguments(), null, navigatorExtras);
    }

    /**
     * Create a deep link to a destination within this NavController.
     *
     * @return a {@link NavDeepLinkBuilder} suitable for constructing a deep link
     */
    @NonNull
    public NavDeepLinkBuilder createDeepLink() {
        return new NavDeepLinkBuilder(this);
    }

    /**
     * Saves all navigation controller state to a Bundle.
     *
     * <p>State may be restored from a bundle returned from this method by calling
     * {@link #restoreState(Bundle)}. Saving controller state is the responsibility
     * of a {@link NavHost}.</p>
     *
     * @return saved state for this controller
     */
    @CallSuper
    @Nullable
    public Bundle saveState() {
        Bundle b = null;
        ArrayList<String> navigatorNames = new ArrayList<>();
        Bundle navigatorState = new Bundle();
        for (Map.Entry<String, Navigator<? extends NavDestination>> entry :
                mNavigatorProvider.getNavigators().entrySet()) {
            String name = entry.getKey();
            Bundle savedState = entry.getValue().onSaveState();
            if (savedState != null) {
                navigatorNames.add(name);
                navigatorState.putBundle(name, savedState);
            }
        }
        if (!navigatorNames.isEmpty()) {
            b = new Bundle();
            navigatorState.putStringArrayList(KEY_NAVIGATOR_STATE_NAMES, navigatorNames);
            b.putBundle(KEY_NAVIGATOR_STATE, navigatorState);
        }
        if (!mBackStack.isEmpty()) {
            if (b == null) {
                b = new Bundle();
            }
            Parcelable[] backStack = new Parcelable[mBackStack.size()];
            int index = 0;
            for (NavBackStackEntry backStackEntry : mBackStack) {
                backStack[index++] = new NavBackStackEntryState(backStackEntry);
            }
            b.putParcelableArray(KEY_BACK_STACK, backStack);
        }
        if (mDeepLinkHandled) {
            if (b == null) {
                b = new Bundle();
            }
            b.putBoolean(KEY_DEEP_LINK_HANDLED, mDeepLinkHandled);
        }
        return b;
    }

    /**
     * Restores all navigation controller state from a bundle. This should be called before any
     * call to {@link #setGraph}.
     *
     * <p>State may be saved to a bundle by calling {@link #saveState()}.
     * Restoring controller state is the responsibility of a {@link NavHost}.</p>
     *
     * @param navState state bundle to restore
     */
    @CallSuper
    public void restoreState(@Nullable Bundle navState) {
        if (navState == null) {
            return;
        }

        navState.setClassLoader(mContext.getClassLoader());

        mNavigatorStateToRestore = navState.getBundle(KEY_NAVIGATOR_STATE);
        mBackStackToRestore = navState.getParcelableArray(KEY_BACK_STACK);
        mDeepLinkHandled = navState.getBoolean(KEY_DEEP_LINK_HANDLED);
    }

    void setLifecycleOwner(@NonNull LifecycleOwner owner) {
        mLifecycleOwner = owner;
        mLifecycleOwner.getLifecycle().addObserver(mLifecycleObserver);
    }

    void setOnBackPressedDispatcher(@NonNull OnBackPressedDispatcher dispatcher) {
        if (mLifecycleOwner == null) {
            throw new IllegalStateException("You must call setLifecycleOwner() before calling "
                    + "setOnBackPressedDispatcher()");
        }
        // Remove the callback from any previous dispatcher
        mOnBackPressedCallback.remove();
        // Then add it to the new dispatcher
        dispatcher.addCallback(mLifecycleOwner, mOnBackPressedCallback);
    }

    void enableOnBackPressed(boolean enabled) {
        mEnableOnBackPressedCallback = enabled;
        updateOnBackPressedCallbackEnabled();
    }

    private void updateOnBackPressedCallbackEnabled() {
        mOnBackPressedCallback.setEnabled(mEnableOnBackPressedCallback
                && getDestinationCountOnBackStack() > 1);
    }

    void setViewModelStore(@NonNull ViewModelStore viewModelStore) {
        if (!mBackStack.isEmpty()) {
            throw new IllegalStateException("ViewModelStore should be set before setGraph call");
        }
        mViewModel = NavControllerViewModel.getInstance(viewModelStore);
    }

    /**
     * Gets the {@link ViewModelStoreOwner} for a NavGraph. This can be passed to
     * {@link androidx.lifecycle.ViewModelProvider} to retrieve a ViewModel that is scoped
     * to the navigation graph - it will be cleared when the navigation graph is popped off
     * the back stack.
     *
     * @param navGraphId ID of a NavGraph that exists on the back stack
     * @throws IllegalStateException if called before the {@link NavHost} has called
     * {@link NavHostController#setViewModelStore}.
     * @throws IllegalArgumentException if the NavGraph is not on the back stack
     */
    @NonNull
    public ViewModelStoreOwner getViewModelStoreOwner(@IdRes int navGraphId) {
        if (mViewModel == null) {
            throw new IllegalStateException("You must call setViewModelStore() before calling "
                    + "getViewModelStoreOwner().");
        }
        NavBackStackEntry lastFromBackStack = getBackStackEntry(navGraphId);
        if (!(lastFromBackStack.getDestination() instanceof NavGraph)) {
            throw new IllegalArgumentException("No NavGraph with ID " + navGraphId
                    + " is on the NavController's back stack");
        }
        return lastFromBackStack;
    }

    /**
     * Gets the topmost {@link NavBackStackEntry} for a destination id.
     * <p>
     * This is always safe to use with {@link #getCurrentDestination() the current destination} or
     * {@link NavDestination#getParent() its parent} or grandparent navigation graphs as these
     * destinations are guaranteed to be on the back stack.
     *
     * @param destinationId ID of a destination that exists on the back stack
     * @throws IllegalArgumentException if the destination is not on the back stack
     */
    @NonNull
    public NavBackStackEntry getBackStackEntry(@IdRes int destinationId) {
        NavBackStackEntry lastFromBackStack = null;
        Iterator<NavBackStackEntry> iterator = mBackStack.descendingIterator();
        while (iterator.hasNext()) {
            NavBackStackEntry entry = iterator.next();
            NavDestination destination = entry.getDestination();
            if (destination.getId() == destinationId) {
                lastFromBackStack = entry;
                break;
            }
        }
        if (lastFromBackStack == null) {
            throw new IllegalArgumentException("No destination with ID " + destinationId
                    + " is on the NavController's back stack. The current destination is "
                    + getCurrentDestination());
        }
        return lastFromBackStack;
    }

    /**
     * Gets the topmost {@link NavBackStackEntry}.
     *
     * @return the topmost entry on the back stack or null if the back stack is empty
     */
    @Nullable
    public NavBackStackEntry getCurrentBackStackEntry() {
        if (mBackStack.isEmpty()) {
            return null;
        } else {
            return mBackStack.getLast();
        }
    }

    /**
     * Gets the previous visible {@link NavBackStackEntry}.
     * <p>
     * This skips over any {@link NavBackStackEntry} that is associated with a {@link NavGraph}.
     *
     * @return the previous visible entry on the back stack or null if the back stack has less
     * than two visible entries
     */
    @Nullable
    public NavBackStackEntry getPreviousBackStackEntry() {
        Iterator<NavBackStackEntry> iterator = mBackStack.descendingIterator();
        // throw the topmost destination away.
        if (iterator.hasNext()) {
            iterator.next();
        }
        while (iterator.hasNext()) {
            NavBackStackEntry entry = iterator.next();
            if (!(entry.getDestination() instanceof NavGraph)) {
                return entry;
            }
        }
        return null;
    }
}
