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

package org.kontalk.ui.view;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.view.View;
import android.view.ViewStub;
import android.widget.LinearLayout;

import org.kontalk.R;
import org.kontalk.data.Contact;
import org.kontalk.util.SystemUtils;


/**
 * Avatar-based message balloon theme.
 * @author Daniele Ricci
 */
public class AvatarMessageTheme extends BaseMessageTheme implements Contact.ContactCallback {

    private static Drawable sDefaultContactImage;

    private final int mDrawableId;

    private LinearLayout mBalloonView;

    private CircleContactBadge mAvatar;

    private Handler mHandler;

    public AvatarMessageTheme(int layoutId, int drawableId) {
        super(layoutId);
        mDrawableId = drawableId;
    }

    @Override
    public View inflate(ViewStub stub) {
        View view = super.inflate(stub);

        mBalloonView = (LinearLayout) view.findViewById(R.id.balloon_view);

        mAvatar = (CircleContactBadge) view.findViewById(R.id.avatar);

        mHandler = new Handler();

        if (sDefaultContactImage == null) {
            sDefaultContactImage = mContext.getResources()
                .getDrawable(R.drawable.ic_contact_picture);
        }

        return view;
    }

    @Override
    public boolean isFullWidth() {
        return false;
    }

    @Override
    public void processComponentView(MessageContentView<?> view) {
        if (view instanceof TextContentView) {
            ((TextContentView) view).enableMeasureHack(true);
        }
    }

    private void setView() {
        if (mBalloonView != null) {
            mBalloonView.setBackgroundResource(mDrawableId);
        }
    }

    @Override
    public void setIncoming(Contact contact) {
        setView();

        if (mAvatar != null) {
            mAvatar.setImageDrawable(sDefaultContactImage);
            if (contact != null) {
                // we mark this with the contact's hash code for the async avatar
                mAvatar.setTag(contact.hashCode());
                mAvatar.assignContactUri(contact.getUri());
                contact.getAvatarAsync(mContext, this);
            }
            else {
                mAvatar.setTag(null);
                mAvatar.assignContactUri(null);
            }
        }

        super.setIncoming(contact);
    }

    @Override
    public void setOutgoing(Contact contact, int status) {
        setView();

        if (mAvatar != null) {
            Drawable avatar;
            Bitmap profile = SystemUtils.getProfilePhoto(mContext);
            if (profile != null) {
                avatar = new BitmapDrawable(mContext.getResources(), profile);
            }
            else {
                avatar = sDefaultContactImage;
            }

            mAvatar.setImageDrawable(avatar);
            mAvatar.assignContactUri(SystemUtils.getProfileUri(mContext));
        }

        super.setOutgoing(contact, status);
    }

    @Override
    public void avatarLoaded(final Contact contact, final Drawable avatar) {
        if (avatar != null) {
            if (mHandler.getLooper().getThread() != Thread.currentThread()) {
                mHandler.post(new Runnable() {
                    public void run() {
                        updateAvatar(contact, avatar);
                    }
                });
            }
            else {
                updateAvatar(contact, avatar);
            }
        }
    }

    private void updateAvatar(Contact contact, Drawable avatar) {
        try {
            // be sure the contact is still the same
            // this is an insane workaround against race conditions
            Integer contactTag = (Integer) mAvatar.getTag();
            if (contactTag != null && contactTag.intValue() == contact.hashCode())
                mAvatar.setImageDrawable(avatar);
        }
        catch (Exception e) {
            // we are deliberately ignoring any exception here
            // because an error here could happen only if something
            // weird is happening, e.g. user leaving the activity
        }
    }

}
