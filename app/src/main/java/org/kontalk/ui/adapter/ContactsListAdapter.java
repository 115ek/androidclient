/*
 * Kontalk Android client
 * Copyright (C) 2015 Kontalk Devteam <devteam@kontalk.org>

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

package org.kontalk.ui.adapter;

import android.content.Context;
import android.database.Cursor;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView.RecyclerListener;
import android.widget.AlphabetIndexer;
import android.widget.ListView;
import android.widget.TextView;

import org.kontalk.R;
import org.kontalk.data.Contact;
import org.kontalk.ui.ContactsListActivity;
import org.kontalk.ui.view.ContactsListItem;

import lb.library.cursor.SearchablePinnedHeaderCursorListViewAdapter;


public class ContactsListAdapter extends SearchablePinnedHeaderCursorListViewAdapter {
    private static final String TAG = ContactsListActivity.TAG;

    private final LayoutInflater mFactory;
    private OnContentChangedListener mOnContentChangedListener;

    public ContactsListAdapter(Context context, ListView list) {
        super(context, null, false);
        setSectionIndexer(new AlphabetIndexer(null, Contact.COLUMN_DISPLAY_NAME,
            "#ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
        mFactory = LayoutInflater.from(context);

        list.setRecyclerListener(new RecyclerListener() {
            public void onMovedToScrapHeap(View view) {
                if (view instanceof ContactsListItem) {
                    ((ContactsListItem) view).unbind();
                }
            }
        });
    }

    public interface OnContentChangedListener {
        void onContentChanged(ContactsListAdapter adapter);
    }

    public void setPinnedHeader(Context context) {
        final TypedValue typedValue = new TypedValue();

        context.getTheme().resolveAttribute(android.R.attr.colorBackground, typedValue, true);
        int pinnedHeaderBackgroundColor = context.getResources().getColor(typedValue.resourceId);
        setPinnedHeaderBackgroundColor(pinnedHeaderBackgroundColor);

        int textColor = context.getResources().getColor(R.color.pinned_header_text);
        setPinnedHeaderTextColor(textColor);
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        final View inflated = mFactory.inflate(R.layout.contacts_list_item, null);
        final ViewHolder holder = new ViewHolder();
        holder.headerView = (TextView) inflated.findViewById(R.id.header_text);
        holder.text1 = (TextView) inflated.findViewById(android.R.id.text1);
        holder.text2 = (TextView) inflated.findViewById(android.R.id.text2);
        inflated.setTag(holder);
        return inflated;
    }

    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        super.bindView(view, context, cursor);

        if (!(view instanceof ContactsListItem)) {
            Log.e(TAG, "Unexpected bound view: " + view);
            return;
        }

        ContactsListItem headerView = (ContactsListItem) view;
        Contact contact = Contact.fromUsersCursor(cursor);
        headerView.bind(context, contact);
    }

    @Override
    protected TextView findHeaderView(View itemView) {
        return ((ViewHolder) itemView.getTag()).headerView;
    }

    @Override
    protected Cursor getFilterCursor(CharSequence charSequence) {
        return null;
    }

    public void setOnContentChangedListener(OnContentChangedListener l) {
        mOnContentChangedListener = l;
    }

    @Override
    protected void onContentChanged() {
        Cursor c = getCursor();
        if (c != null && !c.isClosed() && mOnContentChangedListener != null) {
            mOnContentChangedListener.onContentChanged(this);
        }
    }

    private static class ViewHolder {
        public TextView headerView, text1, text2;
    }
}