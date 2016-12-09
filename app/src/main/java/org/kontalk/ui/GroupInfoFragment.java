/*
 * Kontalk Android client
 * Copyright (C) 2016 Kontalk Devteam <devteam@kontalk.org>

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

package org.kontalk.ui;

import java.text.Collator;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

import com.afollestad.materialdialogs.DialogAction;
import com.afollestad.materialdialogs.MaterialDialog;
import com.akalipetis.fragment.ActionModeListFragment;
import com.akalipetis.fragment.MultiChoiceModeListener;

import org.jxmpp.util.XmppStringUtils;
import org.spongycastle.openpgp.PGPPublicKey;
import org.spongycastle.openpgp.PGPPublicKeyRing;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.view.ActionMode;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.CharacterStyle;
import android.text.style.ForegroundColorSpan;
import android.util.SparseBooleanArray;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import org.kontalk.Kontalk;
import org.kontalk.R;
import org.kontalk.authenticator.Authenticator;
import org.kontalk.client.KontalkGroupManager.KontalkGroup;
import org.kontalk.crypto.PGP;
import org.kontalk.data.Contact;
import org.kontalk.data.Conversation;
import org.kontalk.provider.Keyring;
import org.kontalk.provider.MessagesProviderUtils;
import org.kontalk.provider.MyMessages;
import org.kontalk.provider.MyMessages.Groups;
import org.kontalk.provider.MyUsers;
import org.kontalk.service.msgcenter.MessageCenterService;
import org.kontalk.ui.view.ContactsListItem;
import org.kontalk.util.SystemUtils;


/**
 * Group information fragment
 * FIXME this class is too tied to the concept of "Kontalk group"
 * @author Daniele Ricci
 */
public class GroupInfoFragment extends ActionModeListFragment
        implements Contact.ContactChangeListener, MultiChoiceModeListener {

    private TextView mTitle;
    private Button mSetSubject;
    private Button mLeave;
    private Button mIgnoreAll;
    private MenuItem mRemoveMenu;
    private MenuItem mChatMenu;

    GroupMembersAdapter mMembersAdapter;

    Conversation mConversation;

    private int mCheckedItemCount;

    public static GroupInfoFragment newInstance(long threadId) {
        GroupInfoFragment f = new GroupInfoFragment();
        Bundle data = new Bundle();
        data.putLong("conversation", threadId);
        f.setArguments(data);
        return f;
    }

    private void loadConversation(long threadId) {
        mConversation = Conversation.loadFromId(getContext(), threadId);
        mMembersAdapter.setGroupJid(mConversation.getGroupJid());
        String subject = mConversation.getGroupSubject();
        mTitle.setText(TextUtils.isEmpty(subject) ?
            getString(R.string.group_untitled) : subject);

        String selfJid = Authenticator.getSelfJID(getContext());
        boolean isOwner = KontalkGroup.checkOwnership(mConversation.getGroupJid(), selfJid);
        boolean isMember = mConversation.getGroupMembership() == Groups.MEMBERSHIP_MEMBER;
        mSetSubject.setEnabled(isOwner && isMember);
        mLeave.setEnabled(isMember);

        // load members
        boolean showIgnoreAll = false;
        String[] members = getGroupMembers();
        mMembersAdapter.clear();
        for (String jid : members) {
            Contact c = Contact.findByUserId(getContext(), jid);
            if (c.isKeyChanged() || c.getTrustedLevel() == MyUsers.Keys.TRUST_UNKNOWN)
                showIgnoreAll = true;
            boolean owner = KontalkGroup.checkOwnership(mConversation.getGroupJid(), jid);
            mMembersAdapter.add(c, owner);
        }

        mIgnoreAll.setVisibility(showIgnoreAll ? View.VISIBLE : View.GONE);

        mMembersAdapter.notifyDataSetChanged();
        updateUI();
    }

    private void updateUI() {
        if (mRemoveMenu != null) {
            String selfJid = Authenticator.getSelfJID(getContext());
            boolean isOwner = KontalkGroup.checkOwnership(mConversation.getGroupJid(), selfJid);
            mRemoveMenu.setVisible(isOwner);
        }
    }

    private String[] getGroupMembers() {
        String[] members = mConversation.getGroupPeers();
        String[] added = MessagesProviderUtils.getGroupMembers(getContext(),
            mConversation.getGroupJid(), Groups.MEMBER_PENDING_ADDED);
        if (added.length > 0)
            members = SystemUtils.concatenate(members, added);
        // if we are in the group, add ourself to the list
        if (mConversation.getGroupMembership() == Groups.MEMBERSHIP_MEMBER) {
            String selfJid = Authenticator.getSelfJID(getContext());
            members = SystemUtils.concatenate(members, selfJid);
        }
        return members;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mMembersAdapter = new GroupMembersAdapter(getContext(), null);
        setListAdapter(mMembersAdapter);
        setMultiChoiceModeListener(this);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.group_info, container, false);

        mTitle = (TextView) view.findViewById(R.id.title);

        mSetSubject = (Button) view.findViewById(R.id.btn_change_title);
        mSetSubject.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new MaterialDialog.Builder(getContext())
                    .title(R.string.title_group_subject)
                    .positiveText(android.R.string.ok)
                    .negativeText(android.R.string.cancel)
                    .input(null, mConversation.getGroupSubject(), true, new MaterialDialog.InputCallback() {
                        @Override
                        public void onInput(@NonNull MaterialDialog dialog, CharSequence input) {
                            setGroupSubject(!TextUtils.isEmpty(input) ? input.toString() : null);
                        }
                    })
                    .inputRange(0, Groups.GROUP_SUBJECT_MAX_LENGTH)
                    .show();
            }
        });
        mLeave = (Button) view.findViewById(R.id.btn_leave);
        mLeave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmLeave();
            }
        });
        mIgnoreAll = (Button) view.findViewById(R.id.btn_ignore_all);
        mIgnoreAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new MaterialDialog.Builder(getContext())
                    .title(R.string.title_ignore_all_identities)
                    .content(R.string.msg_ignore_all_identities)
                    .positiveText(android.R.string.ok)
                    .positiveColorRes(R.color.button_danger)
                    .onPositive(new MaterialDialog.SingleButtonCallback() {
                        @Override
                        public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
                            mMembersAdapter.ignoreAll();
                            reload();
                        }
                    })
                    .negativeText(android.R.string.cancel)
                    .show();
            }
        });

        return view;
    }

    void setGroupSubject(String subject) {
        mConversation.setGroupSubject(subject);
        reload();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        return isActionModeActive() || super.onOptionsItemSelected(item);
    }

    public boolean isActionModeActive() {
        return mCheckedItemCount > 0;
    }

    @Override
    public void onItemCheckedStateChanged(ActionMode mode, int position, long id, boolean checked) {
        if (checked)
            mCheckedItemCount++;
        else
            mCheckedItemCount--;
        mode.setTitle(getResources()
            .getQuantityString(R.plurals.context_selected,
                mCheckedItemCount, mCheckedItemCount));
        mode.invalidate();
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_remove:
                // using clone because listview returns its original copy
                removeSelectedUsers(SystemUtils
                    .cloneSparseBooleanArray(getListView().getCheckedItemPositions()));
                mode.finish();
                return true;
            case R.id.menu_chat:
                Contact c = getCheckedItem();
                openChat(c.getJID());
                return true;
        }
        return false;
    }

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
        MenuInflater inflater = mode.getMenuInflater();
        inflater.inflate(R.menu.group_info_ctx, menu);
        mRemoveMenu = menu.findItem(R.id.menu_remove);
        mChatMenu = menu.findItem(R.id.menu_chat);
        updateUI();
        return true;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
        mCheckedItemCount = 0;
        getListView().clearChoices();
        mMembersAdapter.notifyDataSetChanged();
    }

    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
        mChatMenu.setVisible(mCheckedItemCount == 1);
        return true;
    }

    private Contact getCheckedItem() {
        if (mCheckedItemCount != 1)
            throw new IllegalStateException("checked items count must be exactly 1");

        return (Contact) getListView().getItemAtPosition(getCheckedItemPosition());
    }

    private int getCheckedItemPosition() {
        SparseBooleanArray checked = getListView().getCheckedItemPositions();
        return checked.keyAt(checked.indexOfValue(true));
    }

    private void removeSelectedUsers(final SparseBooleanArray checked) {
        boolean removingSelf = false;
        List<String> users = new LinkedList<>();
        for (int i = 0, c = mMembersAdapter.getCount(); i < c; ++i) {
            if (checked.get(i)) {
                Contact contact = (Contact) mMembersAdapter.getItem(i);
                if (Authenticator.isSelfJID(getContext(), contact.getJID())) {
                    removingSelf = true;
                }
                else {
                    users.add(contact.getJID());
                }
            }
        }

        if (users.size() > 0) {
            mConversation.removeUsers(users.toArray(new String[users.size()]));
            reload();
        }

        if (removingSelf)
            confirmLeave();
    }

    @Override
    public void onContactInvalidated(String userId) {
        Activity context = getActivity();
        if (context != null) {
            context.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // just reload
                    reload();
                }
            });
        }
    }

    @Override
    public void onListItemClick(ListView l, View v, int position, long id) {
        int choiceMode = l.getChoiceMode();
        if (choiceMode == ListView.CHOICE_MODE_NONE || choiceMode == ListView.CHOICE_MODE_SINGLE) {
            // open identity dialog
            // one day this will be the contact info activity
            showIdentityDialog(((ContactsListItem) v).getContact());
        }
        else {
            super.onListItemClick(l, v, position, id);
        }
    }

    private void showIdentityDialog(Contact c) {
        final String jid = c.getJID();
        final String dialogFingerprint;
        final String fingerprint;
        final boolean selfJid = Authenticator.isSelfJID(getContext(), jid);
        int titleResId = R.string.title_identity;
        String uid;

        PGPPublicKeyRing publicKey = Keyring.getPublicKey(getContext(), jid, MyUsers.Keys.TRUST_UNKNOWN);
        if (publicKey != null) {
            PGPPublicKey pk = PGP.getMasterKey(publicKey);
            String rawFingerprint = PGP.getFingerprint(pk);
            fingerprint = PGP.formatFingerprint(rawFingerprint);

            uid = PGP.getUserId(pk, XmppStringUtils.parseDomain(jid));
            dialogFingerprint = selfJid ? null : rawFingerprint;
        }
        else {
            // FIXME using another string
            fingerprint = getString(R.string.peer_unknown);
            uid = null;
            dialogFingerprint = null;
        }

        if (Authenticator.isSelfJID(getContext(), jid)) {
            titleResId = R.string.title_identity_self;
        }

        SpannableStringBuilder text = new SpannableStringBuilder();

        if (c.getName() != null && c.getNumber() != null) {
            text.append(c.getName())
                .append('\n')
                .append(c.getNumber());
        }
        else {
            int start = text.length();
            text.append(uid != null ? uid : c.getJID());
            text.setSpan(SystemUtils.getTypefaceSpan(Typeface.BOLD), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        text.append('\n')
            .append(getString(R.string.text_invitation2))
            .append('\n');

        int start = text.length();
        text.append(fingerprint);
        text.setSpan(SystemUtils.getTypefaceSpan(Typeface.BOLD), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        int trustedLevel;
        if (c.isKeyChanged()) {
            // the key has changed and was not trusted yet
            trustedLevel = MyUsers.Keys.TRUST_UNKNOWN;
        }
        else {
            trustedLevel = c.getTrustedLevel();
        }

        int trustStringId;
        CharacterStyle[] trustSpans;
        switch (trustedLevel) {
            case MyUsers.Keys.TRUST_IGNORED:
                trustStringId = R.string.trust_ignored;
                trustSpans = new CharacterStyle[] {
                    SystemUtils.getTypefaceSpan(Typeface.BOLD),
                    SystemUtils.getColoredSpan(getContext(), R.color.button_danger)
                };
                break;

            case MyUsers.Keys.TRUST_VERIFIED:
                trustStringId = R.string.trust_verified;
                trustSpans = new CharacterStyle[] {
                    SystemUtils.getTypefaceSpan(Typeface.BOLD),
                    SystemUtils.getColoredSpan(getContext(), R.color.button_success)
                };
                break;

            case MyUsers.Keys.TRUST_UNKNOWN:
            default:
                trustStringId = R.string.trust_unknown;
                trustSpans = new CharacterStyle[] {
                    SystemUtils.getTypefaceSpan(Typeface.BOLD),
                    SystemUtils.getColoredSpan(getContext(), R.color.button_danger)
                };
                break;
        }

        text.append('\n').append(getString(R.string.status_label));
        start = text.length();
        text.append(getString(trustStringId));
        for (CharacterStyle span : trustSpans)
            text.setSpan(span, start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        MaterialDialog.Builder builder = new MaterialDialog.Builder(getContext())
            .content(text)
            .title(titleResId);

        if (dialogFingerprint != null) {
            builder.onAny(new MaterialDialog.SingleButtonCallback() {
                @Override
                public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
                    switch (which) {
                        case POSITIVE:
                            // trust the key
                            trustKey(jid, dialogFingerprint, MyUsers.Keys.TRUST_VERIFIED);
                            break;
                        case NEUTRAL:
                            // ignore the key
                            trustKey(jid, dialogFingerprint, MyUsers.Keys.TRUST_IGNORED);
                            break;
                        case NEGATIVE:
                            // untrust the key
                            trustKey(jid, dialogFingerprint, MyUsers.Keys.TRUST_UNKNOWN);
                            break;
                    }
                }
            })
            .positiveText(R.string.button_accept)
            .positiveColorRes(R.color.button_success)
            .neutralText(R.string.button_ignore)
            .negativeText(R.string.button_refuse)
            .negativeColorRes(R.color.button_danger);
        }
        else if (!selfJid) {
            builder.onPositive(new MaterialDialog.SingleButtonCallback() {
                @Override
                public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
                    openChat(jid);
                }
            })
            .positiveText(R.string.button_private_chat);
        }

        builder.show();
    }

    void openChat(String jid) {
        Intent i = new Intent();
        i.setData(MyMessages.Threads.getUri(jid));
        Activity parent = getActivity();
        parent.setResult(Activity.RESULT_OK, i);
        parent.finish();
    }

    void trustKey(String jid, String fingerprint, int trustLevel) {
        Kontalk.getMessagesController(getContext())
            .setTrustLevelAndRetryMessages(getContext(), jid, fingerprint, trustLevel);
        Contact.invalidate(jid);
        reload();
    }

    void confirmLeave() {
        new MaterialDialog.Builder(getContext())
            .content(R.string.confirm_will_leave_group)
            .positiveText(android.R.string.ok)
            .negativeText(android.R.string.cancel)
            .onPositive(new MaterialDialog.SingleButtonCallback() {
                @Override
                public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
                    // leave group
                    mConversation.leaveGroup();
                    reload();
                }
            })
            .show();
    }

    void reload() {
        // reload conversation data
        Bundle data = getArguments();
        long threadId = data.getLong("conversation");
        loadConversation(threadId);
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (!(context instanceof GroupInfoParent))
            throw new IllegalArgumentException("parent activity must implement " +
                GroupInfoParent.class.getSimpleName());
    }

    private static final class GroupMembersAdapter extends BaseAdapter {
        private final Context mContext;
        private final List<Contact> mMembers;
        private String mOwner;
        private String mGroupJid;

        GroupMembersAdapter(Context context, String groupJid) {
            mContext = context;
            mMembers = new LinkedList<>();
            mGroupJid = groupJid;
        }

        public void setGroupJid(String groupJid) {
            mGroupJid = groupJid;
        }

        public void clear() {
            mMembers.clear();
        }

        @Override
        public void notifyDataSetChanged() {
            Collections.sort(mMembers, new DisplayNameComparator());
            super.notifyDataSetChanged();
        }

        public void add(Contact contact, boolean isOwner) {
            mMembers.add(contact);
            if (isOwner)
                mOwner = contact.getJID();
        }

        @Override
        public int getCount() {
            return mMembers.size();
        }

        @Override
        public Object getItem(int position) {
            return mMembers.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v;
            if (convertView == null) {
                v = newView(parent);
            } else {
                v = convertView;
            }
            bindView(v, position);
            return v;
        }

        private View newView(ViewGroup parent) {
            return LayoutInflater.from(mContext)
                .inflate(R.layout.contact_item, parent, false);
        }

        private void bindView(View v, int position) {
            ContactsListItem view = (ContactsListItem) v;
            Contact contact = (Contact) getItem(position);
            String prependStatus = null;
            CharacterStyle prependStyle = null;
            if (contact.getJID().equalsIgnoreCase(mOwner)) {
                prependStatus = mContext.getString(R.string.group_info_owner_member);
                prependStyle = new ForegroundColorSpan(Color.RED);
            }
            view.bind(mContext, contact, prependStatus, prependStyle);
        }

        public void ignoreAll() {
            synchronized (mMembers) {
                for (Contact c : mMembers) {
                    if (c.isKeyChanged() || c.getTrustedLevel() == MyUsers.Keys.TRUST_UNKNOWN) {
                        String fingerprint = c.getFingerprint();
                        Keyring.setTrustLevel(mContext, c.getJID(), fingerprint, MyUsers.Keys.TRUST_IGNORED);
                        Contact.invalidate(c.getJID());
                    }
                }
                MessageCenterService.retryMessagesTo(mContext, mGroupJid);
            }
        }

        static class DisplayNameComparator implements
            Comparator<Contact> {
            DisplayNameComparator() {
                mCollator.setStrength(Collator.PRIMARY);
            }

            public final int compare(Contact a, Contact b) {
                String sa = !TextUtils.isEmpty(a.getName()) ?
                    a.getName() : a.getNumber();
                String sb = !TextUtils.isEmpty(b.getName()) ?
                    b.getName() : b.getNumber();

                return mCollator.compare(sa, sb);
            }

            private final Collator mCollator = Collator.getInstance();
        }

    }

    public interface GroupInfoParent {

        void dismiss();

    }

}
