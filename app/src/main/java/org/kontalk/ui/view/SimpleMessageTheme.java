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

package org.kontalk.ui.view;

import android.view.Gravity;
import android.view.View;
import android.view.ViewStub;
import android.widget.LinearLayout;

import org.kontalk.R;
import org.kontalk.data.Contact;


/**
 * Simple drawable-based message theme.
 * @author Daniele Ricci
 */
public class SimpleMessageTheme extends BaseMessageTheme {

    private final int mIncomingDrawableId;
    private final int mOutgoingDrawableId;

    private LinearLayout mBalloonView;
    private LinearLayout mParentView;

    public SimpleMessageTheme(int incomingDrawableId, int outgoingDrawableId) {
        this(R.layout.balloon_base_noavatar, incomingDrawableId, outgoingDrawableId);
    }

    protected SimpleMessageTheme(int layoutId, int incomingDrawableId, int outgoingDrawableId) {
        super(layoutId);
        mIncomingDrawableId = incomingDrawableId;
        mOutgoingDrawableId = outgoingDrawableId;
    }

    @Override
    public View inflate(ViewStub stub) {
        View view = super.inflate(stub);
        mBalloonView = (LinearLayout) view.findViewById(R.id.balloon_view);
        mParentView = (LinearLayout) view.findViewById(R.id.message_view_parent);
        return view;
    }

    @Override
    public void processComponentView(MessageContentView<?> view) {
        if (view instanceof TextContentView) {
            ((TextContentView) view).enableMeasureHack(true);
        }
    }

    @Override
    public void setIncoming(Contact contact) {
        if (mBalloonView != null) {
            mBalloonView.setBackgroundResource(mIncomingDrawableId);
        }
        mParentView.setGravity(Gravity.LEFT);

        super.setIncoming(contact);
    }

    @Override
    public void setOutgoing(Contact contact, int status) {
        if (mBalloonView != null) {
            mBalloonView.setBackgroundResource(mOutgoingDrawableId);
        }
        mParentView.setGravity(Gravity.RIGHT);

        super.setOutgoing(contact, status);
    }

}
