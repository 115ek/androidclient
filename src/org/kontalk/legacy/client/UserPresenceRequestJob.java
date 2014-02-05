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

package org.kontalk.legacy.client;

import java.io.IOException;

import org.kontalk.legacy.client.Protocol.UserPresenceSubscribeRequest;
import org.kontalk.legacy.service.ClientThread;
import org.kontalk.legacy.service.RequestJob;
import org.kontalk.legacy.service.RequestListener;

import android.content.Context;

public class UserPresenceRequestJob extends RequestJob {
    private final String mUserId;
    private final int mEvents;

    public UserPresenceRequestJob(String userId, int events) {
        this.mUserId = userId;
        this.mEvents = events;
    }

    @Override
    public String execute(ClientThread client, RequestListener listener, Context context)
            throws IOException {
        UserPresenceSubscribeRequest.Builder b = UserPresenceSubscribeRequest.newBuilder();
        b.setUserId(mUserId);
        b.setEvents(mEvents);
        return client.getConnection().send(b.build());
    }

    public String getUserId() {
        return mUserId;
    }

}
