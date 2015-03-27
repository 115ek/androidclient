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

import java.util.HashMap;

import org.jxmpp.util.XmppStringUtils;
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
import org.kontalk.provider.MyUsers.Users;
import org.kontalk.sync.SyncAdapter;
import org.kontalk.util.MessageUtils;
import org.kontalk.util.Preferences;
import org.kontalk.util.XMPPUtils;


public class UsersProvider extends ContentProvider {
    public static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".users";

    private static final int DATABASE_VERSION = 7;
    private static final String DATABASE_NAME = "users.db";
    private static final String TABLE_USERS = "users";
    private static final String TABLE_USERS_OFFLINE = "users_offline";

    private static final int USERS = 1;
    private static final int USERS_JID = 2;

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

        // any upgrade - just replace the table
        private static final String[] SCHEMA_UPGRADE = {
            "DROP TABLE IF EXISTS " + TABLE_USERS,
            SCHEMA_USERS,
            "DROP TABLE IF EXISTS " + TABLE_USERS_OFFLINE,
            SCHEMA_USERS_OFFLINE,
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
            mNew = true;
        }

        /** TODO simplify upgrade process based on org.kontalk database schema */
        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion != newVersion) {
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

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        boolean offline = Boolean.parseBoolean(uri.getQueryParameter(Users.OFFLINE));

        int match = sUriMatcher.match(uri);
        switch (match) {
            case USERS:
                qb.setTables(offline ? TABLE_USERS_OFFLINE : TABLE_USERS);
                qb.setProjectionMap(usersProjectionMap);
                break;

            case USERS_JID:
                qb.setTables(offline ? TABLE_USERS_OFFLINE : TABLE_USERS);
                qb.setProjectionMap(usersProjectionMap);
                // TODO append to selection
                String userId = uri.getPathSegments().get(1);
                selection = Users.JID + " = ?";
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
        boolean isResync = Boolean.parseBoolean(uri.getQueryParameter(Users.RESYNC));
        boolean bootstrap = Boolean.parseBoolean(uri.getQueryParameter(Users.BOOTSTRAP));
        boolean commit = Boolean.parseBoolean(uri.getQueryParameter(Users.COMMIT));

        if (isResync) {
            if (!bootstrap || dbHelper.isNew())
                return resync(commit);
            return 0;
        }

        // simple update
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        boolean offline = Boolean.parseBoolean(uri.getQueryParameter(Users.OFFLINE));

        int rc = db.update(offline ? TABLE_USERS_OFFLINE : TABLE_USERS, values, selection, selectionArgs);
        if (rc == 0) {
            // insert new record
            values.put(Users.HASH, XmppStringUtils.parseLocalpart(selectionArgs[0]));
            values.put(Users.JID, selectionArgs[0]);
            values.put(Users.NUMBER, selectionArgs[0]);
            values.put(Users.DISPLAY_NAME, getContext().getString(R.string.peer_unknown));
            values.put(Users.REGISTERED, true);

            db.insert(offline ? TABLE_USERS_OFFLINE : TABLE_USERS, null, values);
            return 1;
        }

        return rc;
    }

    /** Triggers a complete resync of the users database. */
    private int resync(boolean commit) {
        Context context = getContext();
        ContentResolver cr = context.getContentResolver();
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        // begin transaction
        beginTransaction(db);
        boolean success = false;

        if (commit) {
            try {
                // copy contents from offline
                db.execSQL("DELETE FROM " + TABLE_USERS);
                db.execSQL("INSERT INTO " + TABLE_USERS + " SELECT * FROM " + TABLE_USERS_OFFLINE);
                // time to invalidate contacts cache
                Contact.invalidate();
                success = setTransactionSuccessful(db);
            }
            catch (SQLException e) {
                // ops :)
                Log.i(SyncAdapter.TAG, "users table commit failed - already committed?", e);
            }
            finally {
                endTransaction(db, success);
            }

            return 0;
        }
        else {
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

            Cursor phones = null;
            String dialPrefix = Preferences.getDialPrefix(context);
            int dialPrefixLen = dialPrefix != null ? dialPrefix.length() : 0;

            try {
                // query for phone numbers
                phones = cr.query(Phone.CONTENT_URI,
                    new String[] { Phone.NUMBER, Phone.DISPLAY_NAME, Phone.LOOKUP_KEY, Phone.CONTACT_ID, RawContacts.ACCOUNT_TYPE },
                    ContactsContract.Contacts.IN_VISIBLE_GROUP + "=1 AND (" +
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

                            stm.clearBindings();
                            stm.bindString(1, hash);
                            stm.bindString(2, number);
                            stm.bindString(3, XMPPUtils.createLocalJID(getContext(), hash));
                            stm.bindString(4, name);
                            stm.bindString(5, phones.getString(2));
                            stm.bindLong(6, phones.getLong(3));
                            stm.bindLong(7, 0);
                            stm.bindNull(8);
                            stm.bindNull(9);
                            stm.executeInsert();
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

                                stm.clearBindings();
                                stm.bindString(1, hash);
                                stm.bindString(2, number);
                                stm.bindString(3, XMPPUtils.createLocalJID(getContext(), hash));
                                stm.bindString(4, name);
                                stm.bindNull(5);
                                stm.bindLong(6, phones.getLong(phones.getColumnIndex(BaseColumns._ID)));
                                stm.bindLong(7, 0);
                                stm.bindNull(8);
                                stm.bindNull(9);
                                stm.executeInsert();
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
                String ownName = Authenticator.getDefaultDisplayName(getContext());
                String fingerprint = null;
                byte[] publicKeyData = null;
                try {
                    PersonalKey myKey = ((Kontalk) getContext().getApplicationContext())
                        .getPersonalKey();
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

                    stm.clearBindings();
                    stm.bindString(1, hash);
                    stm.bindString(2, ownNumber);
                    stm.bindString(3, XMPPUtils.createLocalJID(getContext(), hash));
                    stm.bindString(4, ownName);
                    stm.bindNull(5);
                    stm.bindNull(6);
                    stm.bindLong(7, 1);
                    if (fingerprint != null)
                        stm.bindString(8, fingerprint);
                    else
                        stm.bindNull(8);
                    if (publicKeyData != null)
                        stm.bindBlob(9, publicKeyData);
                    else
                        stm.bindNull(9);
                    stm.executeInsert();
                    count++;
                }
                catch (IllegalArgumentException iae) {
                    Log.w(SyncAdapter.TAG, "doing sync with no server?");
                }
                catch (SQLiteConstraintException sqe) {
                    // skip duplicate number
                }

                success = setTransactionSuccessful(db);
            }
            finally {
                endTransaction(db, success);
                if (phones != null)
                    phones.close();
                stm.close();
            }
            return count;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        boolean offline = Boolean.parseBoolean(uri.getQueryParameter(Users.OFFLINE));

        String table = offline ? TABLE_USERS_OFFLINE : TABLE_USERS;
        long id = 0;

        try {
            id = db.insertOrThrow(table, null, values);
        }
        catch (SQLException e) {
            String hash = values.getAsString(Users.HASH);
            if (hash != null) {
                // discard display_name if requested
                boolean discardName = Boolean.parseBoolean(uri
                        .getQueryParameter(Users.DISCARD_NAME));
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
        context.getContentResolver().update(Users.CONTENT_URI, registeredValues,
            Users.JID + "=?", new String[] { jid });
    }

    /** Returns a {@link Coder} instance for encrypting data. */
    public static Coder getEncryptCoder(Context context, EndpointServer server, PersonalKey key, String[] recipients) {
        // get recipients public keys from users database
        PGPPublicKeyRing keys[] = new PGPPublicKeyRing[recipients.length];
        for (int i = 0; i < recipients.length; i++) {
            PGPPublicKeyRing ring = getPublicKey(context, recipients[i]);
            if (ring == null)
                throw new IllegalArgumentException("public key not found for user " + recipients[i]);

            keys[i] = ring;
        }

        return new PGPCoder(server, key, keys);
    }

    /** Returns a {@link Coder} instance for decrypting data. */
    public static Coder getDecryptCoder(Context context, EndpointServer server, PersonalKey key, String sender) {
        PGPPublicKeyRing senderKey = getPublicKey(context, sender);
        return new PGPCoder(server, key, senderKey);
    }

    /** Retrieves the public key for a user. */
    public static PGPPublicKeyRing getPublicKey(Context context, String jid) {
        byte[] keydata = null;
        ContentResolver res = context.getContentResolver();
        Cursor c = res.query(Users.CONTENT_URI,
                new String[] { Users.PUBLIC_KEY },
                Users.JID + "=?",
                new String[] { jid },
                null);

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

    /** Retrieves the last seen timestamp for a user. */
    public static long getLastSeen(Context context, String jid) {
        long timestamp = -1;
        ContentResolver res = context.getContentResolver();
        Cursor c = res.query(Users.CONTENT_URI,
            new String[] { Users.LAST_SEEN },
            Users.JID + "=?",
            new String[] { jid },
            null);

        if (c.moveToFirst())
            timestamp = c.getLong(0);

        c.close();

        return timestamp;
    }

    /** Sets the last seen timestamp for a user. */
    public static void setLastSeen(Context context, String jid, long time) {
        ContentValues values = new ContentValues(1);
        values.put(Users.LAST_SEEN, time);
        context.getContentResolver().update(Users.CONTENT_URI, values,
            Users.JID + "=?", new String[]{jid});
    }

    /** Updates a user public key. */
    public static void setUserKey(Context context, String jid, byte[] keydata, String fingerprint) {
        ContentValues values = new ContentValues(2);
        values.put(Users.PUBLIC_KEY, keydata);
        values.put(Users.FINGERPRINT, fingerprint);
        context.getContentResolver().update(Users.CONTENT_URI, values,
            Users.JID + "=?", new String[] { jid });
    }

    public static void setBlockStatus(Context context, String jid, boolean blocked) {
        ContentValues values = new ContentValues(1);
        values.put(Users.BLOCKED, blocked);
        context.getContentResolver().update(Users.CONTENT_URI, values,
            Users.JID + "=?", new String[] { jid });
    }

    /* Transactions compatibility layer */

    @TargetApi(11)
    private void beginTransaction(SQLiteDatabase db) {
        if (android.os.Build.VERSION.SDK_INT >= 11)
            db.beginTransactionNonExclusive();
        else
            // this is because API < 11 doesn't have beginTransactionNonExclusive()
            db.execSQL("BEGIN IMMEDIATE");
    }

    private boolean setTransactionSuccessful(SQLiteDatabase db) {
        if (android.os.Build.VERSION.SDK_INT >= 11)
            db.setTransactionSuccessful();
        return true;
    }

    private void endTransaction(SQLiteDatabase db, boolean success) {
        if (android.os.Build.VERSION.SDK_INT >= 11)
            db.endTransaction();
        else
            db.execSQL(success ? "COMMIT" : "ROLLBACK");
    }

    static {
        sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        sUriMatcher.addURI(AUTHORITY, TABLE_USERS, USERS);
        sUriMatcher.addURI(AUTHORITY, TABLE_USERS + "/*", USERS_JID);

        usersProjectionMap = new HashMap<String, String>();
        usersProjectionMap.put(Users._ID, Users._ID);
        usersProjectionMap.put(Users.HASH, Users.HASH);
        usersProjectionMap.put(Users.NUMBER, Users.NUMBER);
        usersProjectionMap.put(Users.DISPLAY_NAME, Users.DISPLAY_NAME);
        usersProjectionMap.put(Users.JID, Users.JID);
        usersProjectionMap.put(Users.LOOKUP_KEY, Users.LOOKUP_KEY);
        usersProjectionMap.put(Users.CONTACT_ID, Users.CONTACT_ID);
        usersProjectionMap.put(Users.REGISTERED, Users.REGISTERED);
        usersProjectionMap.put(Users.STATUS, Users.STATUS);
        usersProjectionMap.put(Users.LAST_SEEN, Users.LAST_SEEN);
        usersProjectionMap.put(Users.PUBLIC_KEY, Users.PUBLIC_KEY);
        usersProjectionMap.put(Users.FINGERPRINT, Users.FINGERPRINT);
        usersProjectionMap.put(Users.BLOCKED, Users.BLOCKED);
    }

}
