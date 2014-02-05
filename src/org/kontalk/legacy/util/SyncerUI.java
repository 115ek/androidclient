/*
 * Kontalk Android client
 * Copyright (C) 2014 Kontalk Devteam <devteam@kontalk.org>

 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk.legacy.util;

import org.kontalk.legacy.R;
import org.kontalk.legacy.authenticator.Authenticator;
import org.kontalk.legacy.sync.SyncAdapter;
import org.kontalk.legacy.sync.Syncer;
import org.kontalk.legacy.ui.LockedProgressDialog;

import android.app.Activity;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.os.AsyncTask;
import android.util.Log;


/** Helper class to start and monitor a sync operation. */
public abstract class SyncerUI {
    private static final String TAG = SyncerUI.class.getSimpleName();
    private static volatile SyncMonitorTask currentSyncer;

    public static void execute(Activity context, Runnable finish, boolean dialog) {
        if (Authenticator.getDefaultAccount(context) == null) {
            Log.v(TAG, "account does not exists, skipping sync");
            return;
        }

        // another ongoing operation - replace finish runnable
        if (currentSyncer != null) {
            Log.v(TAG, "syncer already monitoring, retaining it");
            currentSyncer.retain(context, finish);
        }
        else {
            if (Syncer.getInstance() == null && !Syncer.isPending()) {
                SyncAdapter.requestSync(context, true);
            }
            Log.v(TAG, "starting sync monitor");
            currentSyncer = new SyncMonitorTask(context, finish, dialog);
            currentSyncer.execute();
        }
    }

    /** Retains an existing sync for monitoring it, if a sync is ever running. */
    public synchronized static boolean retainIfRunning(Activity context, Runnable finish, boolean dialog) {
        if (currentSyncer != null) {
            Log.v(TAG, "syncer already monitoring, retaining it");
            currentSyncer.retain(context, finish);
            return true;
        }
        else if (Syncer.getInstance() != null || Syncer.isPending()) {
            Log.v(TAG, "starting sync monitor");
            currentSyncer = new SyncMonitorTask(context, finish, dialog);
            currentSyncer.execute();
            return true;
        }
        return false;
    }

    public synchronized static boolean isRunning() {
        return (currentSyncer != null && !currentSyncer.isCancelled());
    }

    /**
     * Cancels the current sync monitor (if running).
     * @param discardFinish true to discard reference to {@link SyncMonitorTask#finish}
     */
    public synchronized static void cancel(boolean discardFinish) {
        if (currentSyncer != null) {
            if (discardFinish)
                currentSyncer.finish = null;
            currentSyncer.cancel(true);
            // discard reference immediately
            currentSyncer = null;
        }
    }

    /** Monitors an already running {@link Syncer}. */
    public static final class SyncMonitorTask extends AsyncTask<Syncer, Integer, Boolean> {
        private Activity context;
        private Dialog dialog;
        private Runnable finish;
        private final boolean useDialog;

        public SyncMonitorTask(Activity context, Runnable finish, boolean useDialog) {
            this.context = context;
            this.finish = finish;
            this.useDialog = useDialog;
        }

        @Override
        protected Boolean doInBackground(Syncer... params) {
            if (isCancelled())
                return false;

            while (!isCancelled() && (Syncer.getInstance() != null || Syncer.isPending())) {
                try {
                    Thread.sleep(1000);
                }
                catch (InterruptedException e)  {
                    // interrupted :)
                    break;
                }
            }
            return true;
        }

        @Override
        protected void onPostExecute(Boolean result) {
            finish(false);
        }

        @Override
        protected void onPreExecute() {
            // TODO this does not handle retain() calls
            if (useDialog) {
                ProgressDialog dg = new LockedProgressDialog(context);
                dg.setMessage(context.getString(R.string.msg_sync_progress));
                dg.setCancelable(true);
                dg.setIndeterminate(true);
                dg.setOnCancelListener(new DialogInterface.OnCancelListener() {
                    public void onCancel(DialogInterface dialog) {
                        cancel(true);
                    }
                });

                dialog = dg;
                dg.show();
            }
        }

        /** This is because of a pre-Froyo bug. */
        @Override
        protected void onCancelled() {
            onCancelled(false);
        }

        @Override
        protected void onCancelled(Boolean result) {
            finish(true);
        }

        private void finish(boolean cancel) {
            if (!cancel)
                // discard reference
                currentSyncer = null;

            // dismiss status dialog
            if (dialog != null)
                dialog.dismiss();

            try {
                finish.run();
            }
            catch (Exception e) {
                // ignored
            }
        }

        public void retain(Activity context, Runnable action) {
            this.finish = action;
            this.context = context;
        }
    }


}
