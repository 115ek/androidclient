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

package org.kontalk.provider;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.android.providers.contacts.ContactLocaleUtils;
import com.android.providers.contacts.FastScrollingIndexCache;

import org.jxmpp.util.XmppStringUtils;
import org.spongycastle.openpgp.PGPException;
import org.spongycastle.openpgp.PGPPublicKeyRing;

import android.annotation.TargetApi;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.database.sqlite.SQLiteStatement;
import android.net.Uri;
import android.os.Bundle;
import android.provider.BaseColumns;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.RawContacts;
import android.util.Log;

import org.kontalk.BuildConfig;
import org.kontalk.Kontalk;
import org.kontalk.R;
import org.kontalk.authenticator.Authenticator;
import org.kontalk.client.EndpointServer;
import org.kontalk.client.NumberValidator;
import org.kontalk.crypto.Coder;
import org.kontalk.crypto.PGP;
import org.kontalk.crypto.PGPCoder;
import org.kontalk.crypto.PersonalKey;
import org.kontalk.data.Contact;
import org.kontalk.provider.MyUsers.Keys;
import org.kontalk.provider.MyUsers.Users;
import org.kontalk.sync.SyncAdapter;
import org.kontalk.util.MessageUtils;
import org.kontalk.util.Preferences;
import org.kontalk.util.XMPPUtils;


/**
 * The users provider. Also stores the key trust database.
 * Fast scrolling cache from Google AOSP.
 * @author Daniele Ricci
 */
public class UsersProvider extends ContentProvider {
    public static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".users";

    private static final int DATABASE_VERSION = 8;
    private static final String DATABASE_NAME = "users.db";
    private static final String TABLE_USERS = "users";
    private static final String TABLE_USERS_OFFLINE = "users_offline";
    private static final String TABLE_KEYS = "keys";

    private static final int USERS = 1;
    private static final int USERS_JID = 2;
    private static final int KEYS = 3;
    private static final int KEYS_JID = 4;

    private long mLastResync;

    private FastScrollingIndexCache mFastScrollingIndexCache;
    private ContactLocaleUtils mLocaleUtils;

    private DatabaseHelper dbHelper;
    private static final UriMatcher sUriMatcher;
    private static HashMap<String, String> usersProjectionMap;

    private static class DatabaseHelper extends SQLiteOpenHelper {
        private static final String CREATE_TABLE_USERS = "(" +
            "_id INTEGER PRIMARY KEY," +
            "hash TEXT NOT NULL UNIQUE," +
            "number TEXT NOT NULL UNIQUE," +
            "display_name TEXT," +
            "jid TEXT," +
            "lookup_key TEXT," +
            "contact_id INTEGER," +
            "registered INTEGER NOT NULL DEFAULT 0," +
            "status TEXT," +
            "last_seen INTEGER," +
            "public_key BLOB," +
            "fingerprint TEXT," +
            "blocked INTEGER NOT NULL DEFAULT 0" +
            ")";

        /** This table will contain all the users in contact list .*/
        private static final String SCHEMA_USERS =
            "CREATE TABLE " + TABLE_USERS + " " + CREATE_TABLE_USERS;

        private static final String SCHEMA_USERS_OFFLINE =
            "CREATE TABLE " + TABLE_USERS_OFFLINE + CREATE_TABLE_USERS;

        private static final String CREATE_TABLE_KEYS = "(" +
            "jid TEXT PRIMARY KEY," +
            "public_key BLOB," +
            "fingerprint TEXT" +
            ")";

        /** This table will contain keys verified (and trusted) by the user. */
        private static final String SCHEMA_KEYS =
            "CREATE TABLE " + TABLE_KEYS + " " + CREATE_TABLE_KEYS;

        private static final String[] SCHEMA_UPGRADE_V7 = {
            SCHEMA_KEYS,
            "INSERT INTO " + TABLE_KEYS + " SELECT jid, public_key, fingerprint FROM " + TABLE_USERS,
        };

        // any upgrade - just replace the table
        private static final String[] SCHEMA_UPGRADE = {
            "DROP TABLE IF EXISTS " + TABLE_USERS,
            SCHEMA_USERS,
            "DROP TABLE IF EXISTS " + TABLE_USERS_OFFLINE,
            SCHEMA_USERS_OFFLINE,
            "DROP TABLE IF EXISTS " + TABLE_KEYS,
            SCHEMA_KEYS,
        };

        private Context mContext;

        /** This will be set to true when database is new. */
        private boolean mNew;
        /** A read-only connection to the database. */
        private SQLiteDatabase dbReader;

        protected DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
            mContext = context;
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL(SCHEMA_USERS);
            db.execSQL(SCHEMA_USERS_OFFLINE);
            db.execSQL(SCHEMA_KEYS);
            mNew = true;
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            switch (oldVersion) {
                case 7:
                    // create keys table and trust anyone
                    for (String sql : SCHEMA_UPGRADE_V7)
                        db.execSQL(sql);
                    break;
                default:
                    for (String sql : SCHEMA_UPGRADE)
                        db.execSQL(sql);
                    mNew = true;
            }
        }

        @Override
        public void onOpen(SQLiteDatabase db) {
            String path = mContext.getDatabasePath(DATABASE_NAME).getPath();
            dbReader = SQLiteDatabase.openDatabase(path, null, 0);
        }

        public boolean isNew() {
            return mNew;
        }

        @Override
        public synchronized void close() {
            try {
                dbReader.close();
            }
            catch (Exception e) {
                // ignored
            }
            dbReader = null;
            super.close();
        }

        @Override
        public synchronized SQLiteDatabase getReadableDatabase() {
            return (dbReader != null) ? dbReader : super.getReadableDatabase();
        }
    }

    @Override
    public boolean onCreate() {
        dbHelper = new DatabaseHelper(getContext());
        mFastScrollingIndexCache = FastScrollingIndexCache.getInstance(getContext());
        mLocaleUtils = ContactLocaleUtils.getInstance();
        return true;
    }

    @Override
    public String getType(Uri uri) {
        switch (sUriMatcher.match(uri)) {
            case USERS:
                return Users.CONTENT_TYPE;
            case USERS_JID:
                return Users.CONTENT_ITEM_TYPE;

            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    private void invalidateFastScrollingIndexCache() {
        mFastScrollingIndexCache.invalidate();
    }

    private static final class Counter {
        private int value;

        public Counter(int start) {
            this.value = start;
        }

        public void inc() {
            value++;
        }
    }

    /**
     * Computes counts by the address book index labels and returns it as {@link Bundle} which
     * will be appended to a {@link Cursor} as extras.
     */
    private Bundle getFastScrollingIndexExtras(Cursor cursor) {
        try {
            LinkedHashMap<String, Counter> groups = new LinkedHashMap<>();
            int count = cursor.getCount();

            for (int i = 0; i < count; i++) {
                cursor.moveToNext();
                String label = mLocaleUtils.getLabel(cursor.getString(Contact.COLUMN_DISPLAY_NAME));
                Counter counter = groups.get(label);
                if (counter == null) {
                    counter = new Counter(1);
                    groups.put(label, counter);
                }
                else {
                    counter.inc();
                }
            }

            int numLabels = groups.size();
            String labels[] = new String[numLabels];
            int counts[] = new int[numLabels];
            int i = 0;
            for (Map.Entry<String, Counter> entry : groups.entrySet()) {
                labels[i] = entry.getKey();
                counts[i] = entry.getValue().value;
                i++;
            }

            return FastScrollingIndexCache.buildExtraBundle(labels, counts);
        } finally {
            // reset the cursor
            cursor.move(-1);
        }
    }

    /**
     * Add the "fast scrolling index" bundle, generated by {@link #getFastScrollingIndexExtras},
     * to a cursor as extras.  It first checks {@link FastScrollingIndexCache} to see if we
     * already have a cached result.
     */
    private void bundleFastScrollingIndexExtras(UsersCursor cursor, Uri queryUri,
        final SQLiteDatabase db, SQLiteQueryBuilder qb, String selection,
        String[] selectionArgs, String sortOrder, String countExpression) {

        Bundle b;
        // Note even though FastScrollingIndexCache is thread-safe, we really need to put the
        // put-get pair in a single synchronized block, so that even if multiple-threads request the
        // same index at the same time (which actually happens on the phone app) we only execute
        // the query once.
        //
        // This doesn't cause deadlock, because only reader threads get here but not writer
        // threads.  (Writer threads may call invalidateFastScrollingIndexCache(), but it doesn't
        // synchronize on mFastScrollingIndexCache)
        //
        // All reader and writer threads share the single lock object internally in
        // FastScrollingIndexCache, but the lock scope is limited within each put(), get() and
        // invalidate() call, so it won't deadlock.

        // Synchronizing on a non-static field is generally not a good idea, but nobody should
        // modify mFastScrollingIndexCache once initialized, and it shouldn't be null at this point.
        synchronized (mFastScrollingIndexCache) {
            b = mFastScrollingIndexCache.get(
                queryUri, selection, selectionArgs, sortOrder, countExpression);

            if (b == null) {
                // Not in the cache.  Generate and put.
                b = getFastScrollingIndexExtras(cursor);

                mFastScrollingIndexCache.put(queryUri, selection, selectionArgs, sortOrder,
                    countExpression, b);
            }
        }
        cursor.setExtras(b);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        boolean offline = Boolean.parseBoolean(uri.getQueryParameter(Users.OFFLINE));

        // use the same table name as an alias
        String table = offline ? (TABLE_USERS_OFFLINE + " " + TABLE_USERS) :
            TABLE_USERS;
        qb.setTables(table +
            " LEFT OUTER JOIN " + TABLE_KEYS + " ON " +
            TABLE_USERS + "." + Users.JID + "=" +
            TABLE_KEYS + "." + Keys.JID);
        qb.setProjectionMap(usersProjectionMap);

        int match = sUriMatcher.match(uri);
        switch (match) {
            case USERS:
                // nothing to do
                break;

            case USERS_JID:
                // TODO append to selection
                String userId = uri.getPathSegments().get(1);
                selection = TABLE_USERS + "." + Users.JID + " = ?";
                selectionArgs = new String[] { userId };
                break;

            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = qb.query(db, projection, selection, selectionArgs, null, null, sortOrder);
        if (c.getCount() == 0) {
            // request sync
            SyncAdapter.requestSync(getContext(), false);
        }
        else if (Boolean.parseBoolean(uri.getQueryParameter(Users.EXTRA_INDEX))) {
            UsersCursor uc = new UsersCursor(c);
            bundleFastScrollingIndexExtras(uc, uri, db, qb, selection, selectionArgs, sortOrder, null);
            c = uc;
        }

        c.setNotificationUri(getContext().getContentResolver(), uri);
        return c;
    }

    /** Reverse-lookup a userId hash to insert a new record to users table.
     * FIXME this method could take a very long time to complete.
    private void newRecord(SQLiteDatabase db, String matchHash) {
        // lookup all phone numbers until our hash matches
        Context context = getContext();
        final Cursor phones = context.getContentResolver().query(Phone.CONTENT_URI,
            new String[] { Phone.NUMBER, Phone.DISPLAY_NAME, Phone.LOOKUP_KEY, Phone.CONTACT_ID },
            null, null, null);

        try {
            while (phones.moveToNext()) {
                String number = phones.getString(0);

                // a phone number with less than 4 digits???
                if (number.length() < 4)
                    continue;

                // fix number
                try {
                    number = NumberValidator.fixNumber(context, number,
                            Authenticator.getDefaultAccountName(context), null);
                }
                catch (Exception e) {
                    Log.e(TAG, "unable to normalize number: " + number + " - skipping", e);
                    // skip number
                    continue;
                }

                try {
                    String hash = MessageUtils.sha1(number);
                    if (hash.equalsIgnoreCase(matchHash)) {
                        ContentValues values = new ContentValues();
                        values.put(Users.HASH, matchHash);
                        values.put(Users.NUMBER, number);
                        values.put(Users.DISPLAY_NAME, phones.getString(1));
                        values.put(Users.LOOKUP_KEY, phones.getString(2));
                        values.put(Users.CONTACT_ID, phones.getLong(3));
                        db.insert(TABLE_USERS, null, values);
                        break;
                    }
                }
                catch (NoSuchAlgorithmException e) {
                    Log.e(TAG, "unable to generate SHA-1 hash for " + number + " - skipping", e);
                }
                catch (SQLiteConstraintException sqe) {
                    // skip duplicate number
                    break;
                }
            }
        }
        finally {
            phones.close();
        }

    }
    */

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        try {
            boolean isResync = Boolean.parseBoolean(uri.getQueryParameter(Users.RESYNC));
            boolean bootstrap = Boolean.parseBoolean(uri.getQueryParameter(Users.BOOTSTRAP));
            boolean commit = Boolean.parseBoolean(uri.getQueryParameter(Users.COMMIT));

            if (isResync) {
                // we keep this synchronized to allow for the initial resync by the
                // registration activity
                synchronized (this) {
                    long diff = System.currentTimeMillis() - mLastResync;
                    if (diff > 1000 && (!bootstrap || dbHelper.isNew())) {
                        if (commit) {
                            commit();
                            return 0;
                        }
                        else {
                            return resync();
                        }
                    }

                    mLastResync = System.currentTimeMillis();
                    return 0;
                }
            }

            // simple update
            int match = sUriMatcher.match(uri);
            switch (match) {
                case USERS:
                case USERS_JID:
                    return updateUser(values, Boolean.parseBoolean(uri
                        .getQueryParameter(Users.OFFLINE)), selection, selectionArgs);

                case KEYS:
                    return updateKey(values, selection, selectionArgs);

                default:
                    throw new IllegalArgumentException("Unknown URI " + uri);
            }
        }
        finally {
            invalidateFastScrollingIndexCache();
        }
    }

    private int updateUser(ContentValues values, boolean offline, String selection, String[] selectionArgs) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        int rc = db.update(offline ? TABLE_USERS_OFFLINE : TABLE_USERS, values, selection, selectionArgs);
        if (rc == 0) {
            ContentValues insertValues = new ContentValues(values);
            // insert new record
            insertValues.put(Users.HASH, XmppStringUtils.parseLocalpart(selectionArgs[0]));
            insertValues.put(Users.JID, selectionArgs[0]);
            insertValues.put(Users.NUMBER, selectionArgs[0]);
            if (!values.containsKey(Users.DISPLAY_NAME))
                insertValues.put(Users.DISPLAY_NAME, getContext().getString(R.string.peer_unknown));
            insertValues.put(Users.REGISTERED, true);

            db.insert(offline ? TABLE_USERS_OFFLINE : TABLE_USERS, null, insertValues);
            return 1;
        }

        return rc;
    }

    private int updateKey(ContentValues values, String selection, String[] selectionArgs) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        return db.update(TABLE_KEYS, values, selection, selectionArgs);
    }

    /** Commits the offline table to the online table. */
    private void commit() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        // begin transaction
        beginTransaction(db);
        boolean success = false;

        try {
            // copy contents from offline
            db.execSQL("DELETE FROM " + TABLE_USERS);
            db.execSQL("INSERT INTO " + TABLE_USERS + " SELECT * FROM " + TABLE_USERS_OFFLINE);
            success = setTransactionSuccessful(db);
        }
        catch (SQLException e) {
            // ops :)
            Log.i(SyncAdapter.TAG, "users table commit failed - already committed?", e);
        }
        finally {
            endTransaction(db, success);
            // time to invalidate contacts cache
            Contact.invalidate();
        }
    }

    /** Triggers a complete resync of the users database. */
    private int resync() {
        Context context = getContext();
        ContentResolver cr = context.getContentResolver();
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        // begin transaction
        beginTransaction(db);
        boolean success = false;

        int count = 0;

        // delete old users content
        try {
            db.execSQL("DELETE FROM " + TABLE_USERS_OFFLINE);
        }
        catch (SQLException e) {
            // table might not exist - create it! (shouldn't happen since version 4)
            db.execSQL(DatabaseHelper.SCHEMA_USERS_OFFLINE);
        }

        // we are trying to be fast here
        SQLiteStatement stm = db.compileStatement("INSERT INTO " + TABLE_USERS_OFFLINE +
            " (hash, number, jid, display_name, lookup_key, contact_id, registered, public_key, fingerprint)" +
            " VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)");

        // these two statements are used to immediately update data in the online table
        // even if the data is dummy, it will be soon replaced by sync or by manual request
        SQLiteStatement onlineUpd = db.compileStatement("UPDATE " + TABLE_USERS +
            " SET number = ?, display_name = ?, lookup_key = ?, contact_id = ? WHERE hash = ?");
        SQLiteStatement onlineIns = db.compileStatement("INSERT INTO " + TABLE_USERS +
            " (hash, number, jid, display_name, lookup_key, contact_id, registered, public_key, fingerprint)" +
            " VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)");

        Cursor phones = null;
        String dialPrefix = Preferences.getDialPrefix(context);
        int dialPrefixLen = dialPrefix != null ? dialPrefix.length() : 0;

        try {
            String where = !Preferences.getSyncInvisibleContacts(context) ?
                ContactsContract.Contacts.IN_VISIBLE_GROUP + "=1 AND " :
                "";

            // query for phone numbers
            phones = cr.query(Phone.CONTENT_URI,
                new String[] { Phone.NUMBER, Phone.DISPLAY_NAME, Phone.LOOKUP_KEY, Phone.CONTACT_ID, RawContacts.ACCOUNT_TYPE },
                where + " (" +
                // this will filter out RawContacts from Kontalk
                RawContacts.ACCOUNT_TYPE + " IS NULL OR " +
                RawContacts.ACCOUNT_TYPE + " NOT IN (?, ?))",
                new String[] {
                    Authenticator.ACCOUNT_TYPE, Authenticator.ACCOUNT_TYPE_LEGACY
                }, null);

            if (phones != null) {
                while (phones.moveToNext()) {
                    String number = phones.getString(0);
                    String name = phones.getString(1);

                    // buggy provider - skip entry
                    if (name == null || number == null)
                        continue;

                    // remove dial prefix first
                    if (dialPrefix != null && number.startsWith(dialPrefix))
                        number = number.substring(dialPrefixLen);

                    // a phone number with less than 4 digits???
                    if (number.length() < 4)
                        continue;

                    // fix number
                    try {
                        number = NumberValidator.fixNumber(context, number,
                            Authenticator.getDefaultAccountName(context), 0);
                    }
                    catch (Exception e) {
                        Log.e(SyncAdapter.TAG, "unable to normalize number: " + number + " - skipping", e);
                        // skip number
                        continue;
                    }

                    try {
                        String hash = MessageUtils.sha1(number);
                        String lookupKey = phones.getString(2);
                        long contactId = phones.getLong(3);
                        String jid = XMPPUtils.createLocalJID(getContext(), hash);

                        addResyncContact(db, stm, onlineUpd, onlineIns,
                            hash, number, jid, name,
                            lookupKey, contactId, false, null, null);
                        count++;
                    }
                    catch (IllegalArgumentException iae) {
                        Log.w(SyncAdapter.TAG, "doing sync with no server?");
                    }
                    catch (SQLiteConstraintException sqe) {
                        // skip duplicate number
                    }
                }

                phones.close();
            }
            else {
                Log.e(SyncAdapter.TAG, "query to contacts failed!");
            }

            if (Preferences.getSyncSIMContacts(getContext())) {
                // query for SIM contacts
                // column selection doesn't work because of a bug in Android
                // TODO this is a bit unclear...
                try {
                    phones = cr.query(Uri.parse("content://icc/adn/"),
                        null, null, null, null);
                }
                catch (Exception e) {
                    /*
                    On some phones:
                    java.lang.NullPointerException
                        at android.os.Parcel.readException(Parcel.java:1431)
                        at android.database.DatabaseUtils.readExceptionFromParcel(DatabaseUtils.java:185)
                        at android.database.DatabaseUtils.readExceptionFromParcel(DatabaseUtils.java:137)
                        at android.content.ContentProviderProxy.query(ContentProviderNative.java:366)
                        at android.content.ContentResolver.query(ContentResolver.java:372)
                        at android.content.ContentResolver.query(ContentResolver.java:315)
                     */
                    Log.w(SyncAdapter.TAG, "unable to retrieve SIM contacts", e);
                    phones = null;
                }

                if (phones != null) {
                    while (phones.moveToNext()) {
                        String name = phones.getString(phones.getColumnIndex("name"));
                        String number = phones.getString(phones.getColumnIndex("number"));
                        // buggy firmware - skip entry
                        if (name == null || number == null)
                            continue;

                        // remove dial prefix first
                        if (dialPrefix != null && number.startsWith(dialPrefix))
                            number = number.substring(dialPrefixLen);

                        // a phone number with less than 4 digits???
                        if (number.length() < 4)
                            continue;

                        // fix number
                        try {
                            number = NumberValidator.fixNumber(context, number,
                                    Authenticator.getDefaultAccountName(context), 0);
                        }
                        catch (Exception e) {
                            Log.e(SyncAdapter.TAG, "unable to normalize number: " + number + " - skipping", e);
                            // skip number
                            continue;
                        }

                        try {
                            String hash = MessageUtils.sha1(number);
                            String jid = XMPPUtils.createLocalJID(getContext(), hash);
                            long contactId = phones.getLong(phones.getColumnIndex(BaseColumns._ID));

                            addResyncContact(db, stm, onlineUpd, onlineIns,
                                hash, number, jid, name,
                                null, contactId,
                                false, null, null);
                            count++;
                        }
                        catch (IllegalArgumentException iae) {
                            Log.w(SyncAdapter.TAG, "doing sync with no server?");
                        }
                        catch (SQLiteConstraintException sqe) {
                            // skip duplicate number
                        }
                    }
                }
            }

            // try to add account number with display name
            String ownNumber = Authenticator.getDefaultAccountName(getContext());
            if (ownNumber != null) {
                String ownName = Authenticator.getDefaultDisplayName(getContext());
                String fingerprint = null;
                byte[] publicKeyData = null;
                try {
                    PersonalKey myKey = Kontalk.get(getContext()).getPersonalKey();
                    if (myKey != null) {
                        fingerprint = myKey.getFingerprint();
                        publicKeyData = myKey.getEncodedPublicKeyRing();
                    }
                }
                catch (Exception e) {
                    Log.w(SyncAdapter.TAG, "unable to load personal key", e);
                }
                try {
                    String hash = MessageUtils.sha1(ownNumber);
                    String jid = XMPPUtils.createLocalJID(getContext(), hash);

                    addResyncContact(db, stm, onlineUpd, onlineIns,
                        hash, ownNumber, jid, ownName,
                        null, null,
                        true, publicKeyData, fingerprint);
                    count++;
                }
                catch (IllegalArgumentException iae) {
                    Log.w(SyncAdapter.TAG, "doing sync with no server?");
                }
                catch (SQLiteConstraintException sqe) {
                    // skip duplicate number
                }
            }

            success = setTransactionSuccessful(db);
        }
        finally {
            endTransaction(db, success);
            if (phones != null)
                phones.close();
            stm.close();

            // time to invalidate contacts cache (because of updates to online)
            Contact.invalidate();
        }
        return count;
    }

    private void addResyncContact(SQLiteDatabase db, SQLiteStatement stm, SQLiteStatement onlineUpd, SQLiteStatement onlineIns,
        String hash, String number, String jid, String displayName, String lookupKey,
        Long contactId, boolean registered, byte[] publicKey, String fingerprint) {

        int i = 0;

        stm.clearBindings();
        stm.bindString(++i, hash);
        stm.bindString(++i, number);
        stm.bindString(++i, jid);
        if (displayName != null)
            stm.bindString(++i, displayName);
        else
            stm.bindNull(++i);
        if (lookupKey != null)
            stm.bindString(++i, lookupKey);
        else
            stm.bindNull(++i);
        if (contactId != null)
            stm.bindLong(++i, contactId);
        else
            stm.bindNull(++i);
        stm.bindLong(++i, registered ? 1 : 0);
        if (publicKey != null)
            stm.bindBlob(++i, publicKey);
        else
            stm.bindNull(++i);
        if (fingerprint != null)
            stm.bindString(++i, fingerprint);
        else
            stm.bindNull(++i);
        stm.executeInsert();

        // update online entry
        i = 0;
        onlineUpd.clearBindings();
        onlineUpd.bindString(++i, number);
        if (displayName != null)
            onlineUpd.bindString(++i, displayName);
        else
            onlineUpd.bindNull(++i);
        if (lookupKey != null)
            onlineUpd.bindString(++i, lookupKey);
        else
            onlineUpd.bindNull(++i);
        if (contactId != null)
            onlineUpd.bindLong(++i, contactId);
        else
            onlineUpd.bindNull(++i);
        onlineUpd.bindString(++i, hash);
        int rows = executeUpdateDelete(db, onlineUpd);

        // no contact found, insert a new dummy one
        if (rows <= 0) {
            i = 0;
            onlineIns.clearBindings();
            onlineIns.bindString(++i, hash);
            onlineIns.bindString(++i, number);
            onlineIns.bindString(++i, jid);
            if (displayName != null)
                onlineIns.bindString(++i, displayName);
            else
                onlineIns.bindNull(++i);
            if (lookupKey != null)
                onlineIns.bindString(++i, lookupKey);
            else
                onlineIns.bindNull(++i);
            if (contactId != null)
                onlineIns.bindLong(++i, contactId);
            else
                onlineIns.bindNull(++i);
            onlineIns.bindLong(++i, registered ? 1 : 0);
            if (publicKey != null)
                onlineIns.bindBlob(++i, publicKey);
            else
                onlineIns.bindNull(++i);
            if (fingerprint != null)
                onlineIns.bindString(++i, fingerprint);
            else
                onlineIns.bindNull(++i);
            onlineIns.executeInsert();
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        try {
            int match = sUriMatcher.match(uri);
            switch (match) {
                case USERS:
                case USERS_JID:
                    return insertUser(values, Boolean.parseBoolean(uri
                        .getQueryParameter(Users.OFFLINE)), Boolean.parseBoolean(uri
                        .getQueryParameter(Users.DISCARD_NAME)));

                case KEYS:
                case KEYS_JID:
                    return insertKey(values, Boolean.parseBoolean(uri
                        .getQueryParameter(Keys.TRUST)));

                default:
                    throw new IllegalArgumentException("Unknown URI " + uri);
            }
        }
        finally {
            invalidateFastScrollingIndexCache();
        }
    }

    private Uri insertUser(ContentValues values, boolean offline, boolean discardName) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        String table = offline ? TABLE_USERS_OFFLINE : TABLE_USERS;
        long id = 0;

        try {
            id = db.insertOrThrow(table, null, values);
        }
        catch (SQLException e) {
            String hash = values.getAsString(Users.HASH);
            if (hash != null) {
                // discard display_name if requested
                if (discardName) {
                    values.remove(Users.DISPLAY_NAME);
                    values.remove(Users.NUMBER);
                }

                db.update(table, values, Users.HASH + "=?", new String[] { hash });
            }
        }

        if (id >= 0)
            return ContentUris.withAppendedId(Users.CONTENT_URI, id);
        return null;
    }

    private Uri insertKey(ContentValues values, boolean trust) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        String jid = values.getAsString(Keys.JID);
        if (jid == null)
            throw new IllegalArgumentException("no JID provided");

        int rows;

        if (trust) {
            SQLiteStatement stm = db.compileStatement("INSERT OR REPLACE INTO " + TABLE_KEYS +
                " SELECT jid, public_key, fingerprint FROM " + TABLE_USERS + " WHERE jid = ?");
            stm.bindString(1, jid);
            stm.executeInsert();
            rows = 1;
        }
        else {
            try {
                db.insertOrThrow(TABLE_KEYS, null, values);
                rows = 1;
            }
            catch (SQLException e) {
                rows = db.update(TABLE_KEYS, values, Keys.JID + "=?", new String[]{jid});
            }
        }

        if (rows >= 0)
            return Keys.CONTENT_URI.buildUpon().appendPath(jid).build();
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new SQLException("manual delete from users table not supported.");
    }

    // avoid recreating the same object over and over
    private static ContentValues registeredValues;

    /** Marks a user as registered. */
    public static void markRegistered(Context context, String jid) {
        if (registeredValues == null) {
            registeredValues = new ContentValues(1);
            registeredValues.put(Users.REGISTERED, 1);
        }
        // TODO Uri.withAppendedPath(Users.CONTENT_URI, msg.getSender(true))
        context.getContentResolver().update(Users.CONTENT_URI,
            registeredValues, Users.JID+"=?", new String[] { jid });
    }

    /** Returns a {@link Coder} instance for encrypting data. */
    public static Coder getEncryptCoder(Context context, EndpointServer server, PersonalKey key, String[] recipients) {
        // get recipients public keys from users database
        PGPPublicKeyRing keys[] = new PGPPublicKeyRing[recipients.length];
        for (int i = 0; i < recipients.length; i++) {
            PGPPublicKeyRing ring = getPublicKey(context, recipients[i], true);
            if (ring == null)
                throw new IllegalArgumentException("public key not found for user " + recipients[i]);

            keys[i] = ring;
        }

        return new PGPCoder(server, key, keys);
    }

    /** Returns a {@link Coder} instance for decrypting data. */
    public static Coder getDecryptCoder(Context context, EndpointServer server, PersonalKey key, String sender) {
        PGPPublicKeyRing senderKey = getPublicKey(context, sender, true);
        return new PGPCoder(server, key, senderKey);
    }

    /** Retrieves the (un)trusted public key for a user. */
    public static PGPPublicKeyRing getPublicKey(Context context, String jid, boolean trusted) {
        byte[] keydata = null;
        ContentResolver res = context.getContentResolver();
        Cursor c = res.query(Users.CONTENT_URI.buildUpon()
            .appendPath(jid).build(), new String[] { trusted ?
                Keys.TRUSTED_PUBLIC_KEY : Users.PUBLIC_KEY },
            null, null, null);

        if (c.moveToFirst())
            keydata = c.getBlob(0);

        c.close();

        try {
            return PGP.readPublicKeyring(keydata);
        }
        catch (Exception e) {
            // ignored
        }

        return null;
    }

    /** Retrieves the (un)trusted fingerprint for a user. */
    public static String getFingerprint(Context context, String jid, boolean trusted) {
        String fingerprint = null;
        ContentResolver res = context.getContentResolver();
        Cursor c = res.query(Users.CONTENT_URI.buildUpon()
                .appendPath(jid).build(), new String[] { trusted ?
                Keys.TRUSTED_FINGERPRINT : Users.FINGERPRINT },
            null, null, null);

        if (c.moveToFirst())
            fingerprint = c.getString(0);

        c.close();

        return fingerprint;
    }

    /** Retrieves the last seen timestamp for a user. */
    public static long getLastSeen(Context context, String jid) {
        long timestamp = -1;
        ContentResolver res = context.getContentResolver();
        Cursor c = res.query(Users.CONTENT_URI.buildUpon()
            .appendPath(jid).build(), new String[] { Users.LAST_SEEN },
            null, null, null);

        if (c.moveToFirst())
            timestamp = c.getLong(0);

        c.close();

        return timestamp;
    }

    /** Sets the last seen timestamp for a user. */
    public static void setLastSeen(Context context, String jid, long time) {
        ContentValues values = new ContentValues(1);
        values.put(Users.LAST_SEEN, time);
        context.getContentResolver().update(Users.CONTENT_URI,
            values, Users.JID + "=?", new String[] { jid });
    }

    /** Updates a user public key. */
    public static void setUserKey(Context context, String jid, byte[] keydata)
            throws IOException, PGPException {
        String fingerprint = PGP.getFingerprint(keydata);
        ContentValues values = new ContentValues(2);
        values.put(Users.FINGERPRINT, fingerprint);
        values.put(Users.PUBLIC_KEY, keydata);
        context.getContentResolver().update(Users.CONTENT_URI,
            values, Users.JID + "=?", new String[]{jid});
    }

    /** Marks the given user fingerprint as trusted. */
    public static void trustUserKey(Context context, String jid) {
        ContentValues values = new ContentValues(1);
        values.put(Keys.JID, jid);
        context.getContentResolver().insert(Keys.CONTENT_URI.buildUpon()
            .appendQueryParameter(Keys.TRUST, "true")
            .build(), values);
    }

    /** Trusts a user public key if trusted fingerprint matches the given key. */
    public static void maybeTrustUserKey(Context context, String jid, byte[] keydata)
            throws IOException, PGPException {
        String fingerprint = PGP.getFingerprint(keydata);
        ContentValues values = new ContentValues(1);
        values.put(Keys.PUBLIC_KEY, keydata);
        context.getContentResolver().update(Keys.CONTENT_URI,
            values, Keys.JID + "=? AND " + Keys.FINGERPRINT + "=?",
            new String[] { jid, fingerprint });
    }

    public static void setBlockStatus(Context context, String jid, boolean blocked) {
        ContentValues values = new ContentValues(1);
        values.put(Users.BLOCKED, blocked);
        context.getContentResolver().update(Users.CONTENT_URI.buildUpon()
            .appendPath(jid).build(), values, null, null);
    }

    public static int resync(Context context) {
        // update users database
        Uri uri = Users.CONTENT_URI.buildUpon()
            .appendQueryParameter(Users.RESYNC, "true")
            .build();
        return context.getContentResolver().update(uri, new ContentValues(), null, null);
    }

    /* Transactions compatibility layer */

    @TargetApi(android.os.Build.VERSION_CODES.HONEYCOMB)
    private void beginTransaction(SQLiteDatabase db) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.HONEYCOMB)
            db.beginTransactionNonExclusive();
        else
            // this is because API < 11 doesn't have beginTransactionNonExclusive()
            db.execSQL("BEGIN IMMEDIATE");
    }

    private boolean setTransactionSuccessful(SQLiteDatabase db) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.HONEYCOMB)
            db.setTransactionSuccessful();
        return true;
    }

    private void endTransaction(SQLiteDatabase db, boolean success) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.HONEYCOMB)
            db.endTransaction();
        else
            db.execSQL(success ? "COMMIT" : "ROLLBACK");
    }

    private int executeUpdateDelete(SQLiteDatabase db, SQLiteStatement stm) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.HONEYCOMB) {
            return stm.executeUpdateDelete();
        }
        else {
            stm.execute();
            SQLiteStatement changes = db.compileStatement("SELECT changes()");
            try {
                return (int) changes.simpleQueryForLong();
            }
            finally {
                changes.close();
            }
        }
    }

    static {
        sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        sUriMatcher.addURI(AUTHORITY, TABLE_USERS, USERS);
        sUriMatcher.addURI(AUTHORITY, TABLE_USERS + "/*", USERS_JID);
        sUriMatcher.addURI(AUTHORITY, TABLE_KEYS, KEYS);
        sUriMatcher.addURI(AUTHORITY, TABLE_KEYS + "/*", KEYS_JID);

        usersProjectionMap = new HashMap<String, String>();
        usersProjectionMap.put(Users._ID, Users._ID);
        usersProjectionMap.put(Users.HASH, Users.HASH);
        usersProjectionMap.put(Users.NUMBER, Users.NUMBER);
        usersProjectionMap.put(Users.DISPLAY_NAME, Users.DISPLAY_NAME);
        usersProjectionMap.put(Users.JID, TABLE_USERS + "." + Users.JID);
        usersProjectionMap.put(Users.LOOKUP_KEY, Users.LOOKUP_KEY);
        usersProjectionMap.put(Users.CONTACT_ID, Users.CONTACT_ID);
        usersProjectionMap.put(Users.REGISTERED, Users.REGISTERED);
        usersProjectionMap.put(Users.STATUS, Users.STATUS);
        usersProjectionMap.put(Users.LAST_SEEN, Users.LAST_SEEN);
        usersProjectionMap.put(Users.PUBLIC_KEY, TABLE_USERS + "." + Users.PUBLIC_KEY);
        usersProjectionMap.put(Users.FINGERPRINT, TABLE_USERS + "." + Users.FINGERPRINT);
        usersProjectionMap.put(Users.BLOCKED, Users.BLOCKED);
        usersProjectionMap.put(Keys.TRUSTED_PUBLIC_KEY, TABLE_KEYS + "." + Keys.PUBLIC_KEY);
        usersProjectionMap.put(Keys.TRUSTED_FINGERPRINT, TABLE_KEYS + "." + Keys.FINGERPRINT);
    }

}
