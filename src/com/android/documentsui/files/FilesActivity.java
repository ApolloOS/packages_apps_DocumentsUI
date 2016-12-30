/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.documentsui.files;

import static com.android.documentsui.OperationDialogFragment.DIALOG_TYPE_UNKNOWN;
import static com.android.documentsui.base.Shared.DEBUG;

import android.app.Activity;
import android.app.FragmentManager;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.CallSuper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.KeyboardShortcutGroup;
import android.view.Menu;
import android.view.MenuItem;

import com.android.documentsui.ActionModeController;
import com.android.documentsui.BaseActivity;
import com.android.documentsui.DocumentsApplication;
import com.android.documentsui.DragShadowBuilder;
import com.android.documentsui.FocusManager;
import com.android.documentsui.Injector;
import com.android.documentsui.MenuManager.DirectoryDetails;
import com.android.documentsui.OperationDialogFragment;
import com.android.documentsui.OperationDialogFragment.DialogType;
import com.android.documentsui.ProviderExecutor;
import com.android.documentsui.R;
import com.android.documentsui.SharedInputHandler;
import com.android.documentsui.base.DocumentInfo;
import com.android.documentsui.base.DocumentStack;
import com.android.documentsui.base.RootInfo;
import com.android.documentsui.base.ScopedPreferences;
import com.android.documentsui.base.Shared;
import com.android.documentsui.base.State;
import com.android.documentsui.clipping.DocumentClipper;
import com.android.documentsui.dirlist.AnimationView.AnimationType;
import com.android.documentsui.dirlist.DirectoryFragment;
import com.android.documentsui.selection.SelectionManager;
import com.android.documentsui.services.FileOperationService;
import com.android.documentsui.sidebar.RootsFragment;
import com.android.documentsui.ui.DialogController;
import com.android.documentsui.ui.MessageBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Standalone file management activity.
 */
public class FilesActivity extends BaseActivity implements ActionHandler.Addons {

    private static final String TAG = "FilesActivity";
    static final String PREFERENCES_SCOPE = "files";

    private Injector<ActionHandler<FilesActivity>> mInjector;
    private ActivityInputHandler mActivityInputHandler;
    private SharedInputHandler mSharedInputHandler;
    private DragShadowBuilder mShadowBuilder;

    public FilesActivity() {
        super(R.layout.files_activity, TAG);
    }

    @Override
    public void onCreate(Bundle icicle) {

        MessageBuilder messages = new MessageBuilder(this);
        mInjector = new Injector<>(
                new Config(),
                ScopedPreferences.create(this, PREFERENCES_SCOPE),
                messages,
                DialogController.create(this, messages));

        super.onCreate(icicle);

        DocumentClipper clipper = DocumentsApplication.getDocumentClipper(this);
        mInjector.selectionMgr = new SelectionManager(SelectionManager.MODE_MULTIPLE);

        mInjector.focusManager = new FocusManager(
            mInjector.selectionMgr,
            mDrawer,
            this::focusSidebar,
            getColor(R.color.accent_dark));

        mInjector.menuManager = new MenuManager(
                mSearchManager,
                mState,
                new DirectoryDetails(this) {
                    @Override
                    public boolean hasItemsToPaste() {
                        return clipper.hasItemsToPaste();
                    }
                });

        mShadowBuilder = new DragShadowBuilder(this);
        mInjector.actionModeController = new ActionModeController(
                this,
                mInjector.selectionMgr,
                mInjector.menuManager,
                mInjector.messages);

        mInjector.actions = new ActionHandler<>(
                this,
                mState,
                mRoots,
                mDocs,
                mInjector.focusManager,
                mInjector.selectionMgr,
                mSearchManager,
                ProviderExecutor::forAuthority,
                mInjector.actionModeController,
                mInjector.dialogs,
                mInjector.config,
                clipper,
                DocumentsApplication.getClipStore(this));

        mActivityInputHandler =
                new ActivityInputHandler(mInjector.actions::deleteSelectedDocuments);
        mSharedInputHandler = new SharedInputHandler(mInjector.focusManager, this::popDir);

        RootsFragment.show(getFragmentManager(), null);

        final Intent intent = getIntent();

        mInjector.actions.initLocation(intent);
        presentFileErrors(icicle, intent);
    }

    private void presentFileErrors(Bundle icicle, final Intent intent) {
        final @DialogType int dialogType = intent.getIntExtra(
                FileOperationService.EXTRA_DIALOG_TYPE, DIALOG_TYPE_UNKNOWN);
        // DialogFragment takes care of restoring the dialog on configuration change.
        // Only show it manually for the first time (icicle is null).
        if (icicle == null && dialogType != DIALOG_TYPE_UNKNOWN) {
            final int opType = intent.getIntExtra(
                    FileOperationService.EXTRA_OPERATION_TYPE,
                    FileOperationService.OPERATION_COPY);
            final ArrayList<DocumentInfo> docList =
                    intent.getParcelableArrayListExtra(FileOperationService.EXTRA_FAILED_DOCS);
            final ArrayList<DocumentInfo> uriList =
                    intent.getParcelableArrayListExtra(FileOperationService.EXTRA_FAILED_URIS);
            OperationDialogFragment.show(
                    getFragmentManager(),
                    dialogType,
                    docList,
                    uriList,
                    mState.stack,
                    opType);
        }
    }

    @Override
    public void includeState(State state) {
        final Intent intent = getIntent();

        state.action = State.ACTION_BROWSE;
        state.allowMultiple = true;

        // Options specific to the DocumentsActivity.
        assert(!intent.hasExtra(Intent.EXTRA_LOCAL_ONLY));

        final DocumentStack stack = intent.getParcelableExtra(Shared.EXTRA_STACK);
        if (stack != null) {
            state.stack.reset(stack);
        }
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        // This check avoids a flicker from "Recents" to "Home".
        // Only update action bar at this point if there is an active
        // serach. Why? Because this avoid an early (undesired) load of
        // the recents root...which is the default root in other activities.
        // In Files app "Home" is the default, but it is loaded async.
        // update will be called once Home root is loaded.
        // Except while searching we need this call to ensure the
        // search bits get layed out correctly.
        if (mSearchManager.isSearching()) {
            mNavigator.update();
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        final RootInfo root = getCurrentRoot();

        // If we're browsing a specific root, and that root went away, then we
        // have no reason to hang around.
        // TODO: Rather than just disappearing, maybe we should inform
        // the user what has happened, let them close us. Less surprising.
        if (mRoots.getRootBlocking(root.authority, root.rootId) == null) {
            finish();
        }
    }

    @Override
    public String getDrawerTitle() {
        Intent intent = getIntent();
        return (intent != null && intent.hasExtra(Intent.EXTRA_TITLE))
                ? intent.getStringExtra(Intent.EXTRA_TITLE)
                : getString(R.string.app_label);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        mInjector.menuManager.updateOptionMenu(menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_create_dir:
                assert(canCreateDirectory());
                showCreateDirectoryDialog();
                break;
            case R.id.menu_new_window:
                mInjector.actions.openInNewWindow(mState.stack);
                break;
            case R.id.menu_paste_from_clipboard:
                DirectoryFragment dir = getDirectoryFragment();
                if (dir != null) {
                    dir.pasteFromClipboard();
                }
                break;
            case R.id.menu_settings:
                mInjector.actions.openSettings(getCurrentRoot());
                break;
            default:
                return super.onOptionsItemSelected(item);
        }
        return true;
    }

    @Override
    public void onProvideKeyboardShortcuts(
            List<KeyboardShortcutGroup> data, Menu menu, int deviceId) {
        mInjector.menuManager.updateKeyboardShortcutsMenu(data, this::getString);
    }

    @Override
    public void refreshDirectory(@AnimationType int anim) {
        final FragmentManager fm = getFragmentManager();
        final RootInfo root = getCurrentRoot();
        final DocumentInfo cwd = getCurrentDirectory();

        assert(!mSearchManager.isSearching());

        if (cwd == null) {
            DirectoryFragment.showRecentsOpen(fm, anim);
        } else {
            // Normal boring directory
            DirectoryFragment.showDirectory(fm, root, cwd, anim);
        }
    }

    @Override
    public void onDocumentsPicked(List<DocumentInfo> docs) {
        throw new UnsupportedOperationException();
    }

    /**
     * @deprecated use {@link ActionHandler#onDocumentPicked(DocumentInfo)}
     * @param doc
     */
    @Override
    public void onDocumentPicked(DocumentInfo doc) {
        mInjector.actions.onDocumentPicked(doc);
    }

    @Override
    public void onDirectoryCreated(DocumentInfo doc) {
        assert(doc.isDirectory());
        mInjector.focusManager.focusDocument(doc.documentId);
    }

    @Override
    public void springOpenDirectory(DocumentInfo doc) {
        assert(doc.isContainer());
        assert(!doc.isArchive());
        mInjector.actions.openContainerDocument(doc);
    }

    @CallSuper
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return mActivityInputHandler.onKeyDown(keyCode, event)
                || mSharedInputHandler.onKeyDown(keyCode, event)
                || super.onKeyDown(keyCode, event);
    }

    @Override
    public DragShadowBuilder getShadowBuilder() {
        return mShadowBuilder;
    }

    @Override
    public boolean onKeyShortcut(int keyCode, KeyEvent event) {
        DirectoryFragment dir;
        // TODO: All key events should be statically bound using alphabeticShortcut.
        // But not working.
        switch (keyCode) {
            case KeyEvent.KEYCODE_A:
                dir = getDirectoryFragment();
                if (dir != null) {
                    dir.selectAllFiles();
                }
                return true;
            case KeyEvent.KEYCODE_X:
                mInjector.actions.cutToClipboard();
                return true;
            case KeyEvent.KEYCODE_C:
                mInjector.actions.copyToClipboard();
                return true;
            case KeyEvent.KEYCODE_V:
                dir = getDirectoryFragment();
                if (dir != null) {
                    dir.pasteFromClipboard();
                }
                return true;
            default:
                return super.onKeyShortcut(keyCode, event);
        }
    }

    @Override
    public void onTaskFinished(Uri... uris) {
        if (DEBUG) Log.d(TAG, "onFinished() " + Arrays.toString(uris));

        final Intent intent = new Intent();
        if (uris.length == 1) {
            intent.setData(uris[0]);
        } else if (uris.length > 1) {
            final ClipData clipData = new ClipData(
                    null, mState.acceptMimes, new ClipData.Item(uris[0]));
            for (int i = 1; i < uris.length; i++) {
                clipData.addItem(new ClipData.Item(uris[i]));
            }
            intent.setClipData(clipData);
        }

        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        setResult(Activity.RESULT_OK, intent);
        finish();
    }

    @Override
    public Injector<ActionHandler<FilesActivity>> getInjector() {
        return mInjector;
    }
}
