/*******************************************************************************
 * Mirakel is an Android App for managing your ToDo-Lists
 *
 * Copyright (c) 2013-2014 Anatolij Zelenin, Georg Semmler.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/

package de.azapps.mirakel.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import android.annotation.TargetApi;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Build;
import de.azapps.mirakel.DefinitionsHelper;
import de.azapps.mirakel.model.account.AccountMirakel;
import de.azapps.mirakel.model.file.FileMirakel;
import de.azapps.mirakel.model.list.ListMirakel;
import de.azapps.mirakel.model.list.SpecialList;
import de.azapps.mirakel.model.recurring.Recurring;
import de.azapps.mirakel.model.semantic.Semantic;
import de.azapps.mirakel.model.tags.Tag;
import de.azapps.mirakel.model.task.Task;
import de.azapps.tools.Log;

public class MirakelInternalContentProvider extends ContentProvider {

    public interface DBTransaction {
        abstract void exec();
    }

    public static class DataBaseLockedException extends RuntimeException {
        public DataBaseLockedException(final String message) {
            super(message);
        }

        public DataBaseLockedException(final String message,
                                       final Throwable throwable) {
            super(message, throwable);
        }
    }

    private static final String TAG = "MirakelInternalContentProvider";
    private static final List<String> EXISTING_TABLES = new ArrayList<>();

    private static Uri getUri(final String tableName) {
        EXISTING_TABLES.add(tableName);
        return Uri.parse("content://" + DefinitionsHelper.AUTHORITY_INTERNAL
                         + "/" + tableName);
    }


    // Join table constants
    public static final String TASK_TAG_JOIN = "task_tag_join";
    public static final String TASK_RECURRING_TW_JOIN = "task_recurring_tw";
    public static final String LISTS_SORT_JOIN = "lists_sort";
    public static final String TASK_SUBTASK_JOIN = "task_subtask";
    public static final String UPDATE_LIST_ORDER_JOIN = "update_list_order";
    public static final String UPDATE_LIST_MOVE_DOWN = "list_move_down";
    public static final String UPDATE_LIST_MOVE_UP = "list_move_up";
    public static final String UPDATE_LIST_FIX_RGT = "list_fix_rgt";

    // Uris
    public static final Uri TASK_URI = getUri(Task.TABLE);
    public static final Uri TASK_SUBTASK_URI = getUri(TASK_SUBTASK_JOIN);
    public static final Uri TASK_TAG_URI = getUri(TASK_TAG_JOIN);
    public static final Uri TAG_URI = getUri(Tag.TABLE);
    public static final Uri LIST_URI = getUri(ListMirakel.TABLE);
    public static final Uri TAG_CONNECTION_URI = getUri(Tag.TAG_CONNECTION_TABLE);
    public static final Uri CALDAV_LISTS_URI = getUri("caldav_lists");
    public static final Uri CALDAV_TASKS_URI = getUri("caldav_tasks");
    public static final Uri SUBTASK_URI = getUri(Task.SUBTASK_TABLE);
    public static final Uri FILE_URI = getUri(FileMirakel.TABLE);
    public static final Uri RECURRING_TW_URI = getUri(Recurring.TW_TABLE);
    public static final Uri TASK_RECURRING_TW_URI = getUri(TASK_RECURRING_TW_JOIN);
    public static final Uri ACCOUNT_URI = getUri(AccountMirakel.TABLE);
    public static final Uri RECURRING_URI = getUri(Recurring.TABLE);
    public static final Uri SEMANTIC_URI = getUri(Semantic.TABLE);
    public static final Uri SPECIAL_LISTS_URI = getUri(SpecialList.TABLE);
    public static final Uri LISTS_SORT_URI = getUri(LISTS_SORT_JOIN);

    public static final Uri UPDATE_LIST_ORDER_URI = getUri(UPDATE_LIST_ORDER_JOIN);
    public static final Uri UPDATE_LIST_MOVE_DOWN_URI = getUri(UPDATE_LIST_MOVE_DOWN);
    public static final Uri UPDATE_LIST_MOVE_UP_URI = getUri(UPDATE_LIST_MOVE_UP);
    public static final Uri UPDATE_LIST_FIX_RGT_URI = getUri(UPDATE_LIST_FIX_RGT);

    private static final List<String> BLACKLISTED_FOR_MODIFICATIONS = Arrays
            .asList("", TASK_RECURRING_TW_JOIN, TASK_SUBTASK_JOIN, TASK_TAG_JOIN,
                    LISTS_SORT_JOIN);
    private static final List<String> BLACKLISTED_FOR_DELETION = Arrays
            .asList("", TASK_RECURRING_TW_JOIN, TASK_SUBTASK_JOIN, TASK_TAG_JOIN,
                    LISTS_SORT_JOIN, UPDATE_LIST_MOVE_DOWN, UPDATE_LIST_MOVE_UP, UPDATE_LIST_ORDER_JOIN,
                    UPDATE_LIST_FIX_RGT);

    private static final List<String> BLACKLISTED_FOR_QUERY = Arrays.asList(UPDATE_LIST_MOVE_DOWN,
            UPDATE_LIST_MOVE_UP, UPDATE_LIST_ORDER_JOIN, UPDATE_LIST_FIX_RGT);

    private static DatabaseHelper dbHelper = null;
    private static SQLiteDatabase database;
    private static boolean isPreInit = true;

    private static SQLiteDatabase getReadableDatabase() {
        if (database == null && dbHelper != null) {
            return dbHelper.getReadableDatabase();
        }
        return database;
    }

    private static SQLiteDatabase getWritableDatabase() {
        if (database == null && dbHelper != null) {
            return dbHelper.getWritableDatabase();
        }
        return database;
    }

    @Override
    public int delete(final Uri uri, final String selection,
                      final String[] selectionArgs) {
        final String table = getTableName(uri);
        if (BLACKLISTED_FOR_DELETION.contains(table)) {
            throw new IllegalArgumentException(table
                                               + " is blacklisted for delete");
        }
        final SQLiteDatabase db = getWritableDatabase();
        final boolean locked = db.inTransaction();
        if (!locked) {
            db.beginTransaction();
        }
        final int u = db.delete(table, selection, selectionArgs);
        if (!locked) {
            db.setTransactionSuccessful();
            db.endTransaction();
        }
        return u;
    }

    public static String getTableName(final Uri u) {
        final List<String> l = u.getPathSegments();
        if (l.size() > 0 && EXISTING_TABLES.contains(l.get(0))) {
            return l.get(0);
        }
        throw new IllegalArgumentException("Unknown table " + l.get(0));
    }

    @Override
    public String getType(final Uri uri) {
        return null;
    }

    @Override
    public Uri insert(final Uri uri, final ContentValues values) {
        final String table = getTableName(uri);
        if (BLACKLISTED_FOR_MODIFICATIONS.contains(table)) {
            throw new IllegalArgumentException(table
                                               + " is blacklisted for insert");
        }
        final SQLiteDatabase db = getWritableDatabase();
        final boolean locked = db.inTransaction();
        if (!locked) {
            db.beginTransaction();
        }
        final Uri u = ContentUris.withAppendedId(uri,
                      db.insert(table, null, values));
        if (!locked) {
            db.setTransactionSuccessful();
            db.endTransaction();
        }
        return u;
    }

    public static void init(final SQLiteDatabase db, final Context ctx) {
        if (db == null) {
            return;
        }
        database = db;
        if (db == null) {
            Log.w(TAG, "database is null");
        }
    }

    @Override
    public boolean onCreate() {
        if (database == null) {
            dbHelper = new DatabaseHelper(getContext());
            isPreInit = false;
        }
        final ScheduledExecutorService worker = Executors
                                                .newSingleThreadScheduledExecutor();
        worker.schedule(new Runnable() {
            @Override
            public void run() {
                ModelBase.init(getContext());
                Semantic.init(getContext());
            }
        }, 1, TimeUnit.MILLISECONDS);
        return true;
    }

    @Override
    public Cursor query(final Uri uri, final String[] projection,
                        final String selection, final String[] selectionArgs,
                        final String sortOrder) {
        final String table = getTableName(uri);
        final SQLiteQueryBuilder builder = new SQLiteQueryBuilder();
        String groupBy = null;
        if (BLACKLISTED_FOR_QUERY.contains(table)) {
            throw new IllegalArgumentException(table
                                               + " is blacklisted for query");
        }
        switch (table) {
        case TASK_SUBTASK_JOIN:
            builder.setTables(Task.TABLE + " INNER JOIN " + Task.SUBTASK_TABLE
                              + " ON " + Task.TABLE + "." + ModelBase.ID + "="
                              + Task.SUBTASK_TABLE + ".child_id");
            break;
        case TASK_RECURRING_TW_JOIN:
            builder.setTables(Task.TABLE + " INNER JOIN " + Recurring.TW_TABLE
                              + " ON " + Task.TABLE + "." + ModelBase.ID + "="
                              + Recurring.TW_TABLE + "." + Recurring.CHILD);
            break;
        case TASK_TAG_JOIN:
            builder.setTables(Tag.TAG_CONNECTION_TABLE + " INNER JOIN "
                              + Tag.TABLE + " ON " + Tag.TAG_CONNECTION_TABLE
                              + ".tag_id=" + Tag.TABLE + "." + ModelBase.ID);
            break;
        case LISTS_SORT_JOIN:
            builder.setTables(ListMirakel.TABLE + " AS n, " + ListMirakel.TABLE
                              + " AS p ");
            groupBy = "n." + ListMirakel.LFT;
            break;
        default:
            builder.setTables(table);
        }
        final Cursor c = builder.query(getReadableDatabase(), projection,
                                       selection, selectionArgs, groupBy, null, sortOrder);
        if (c == null) {
            Log.wtf(TAG, "cursor to query " + builder.toString() + " is null");
            return new MatrixCursor(projection);
        }
        return c;
    }

    @Override
    public int update(final Uri uri, final ContentValues values,
                      final String selection, final String[] selectionArgs) {
        final String table = getTableName(uri);
        if (BLACKLISTED_FOR_MODIFICATIONS.contains(table)) {
            throw new IllegalArgumentException(table
                                               + " is blacklisted for update");
        }
        final SQLiteDatabase db = getWritableDatabase();
        final boolean locked = db.inTransaction();
        if (!locked) {
            db.beginTransaction();
        }
        String update_table = values.getAsString("TABLE");
        int u = 0;
        switch (table) {
        case UPDATE_LIST_ORDER_JOIN:
            if (selectionArgs != null && selectionArgs.length > 0) {
                throw new IllegalArgumentException("Sorry, but we cannot use selectionsArgs here :(");
            }
            db.execSQL(
                "UPDATE " + update_table + " SET "
                + ListMirakel.LFT + "=" + ListMirakel.LFT
                + "-2 WHERE " + selection);
            db.execSQL(
                "UPDATE " + update_table + " SET "
                + ListMirakel.RGT + "=" + ListMirakel.RGT
                + "-2 WHERE " + selection);
            break;
        case UPDATE_LIST_MOVE_DOWN:
            if (selectionArgs != null && selectionArgs.length > 0) {
                throw new IllegalArgumentException("Sorry, but we cannot use selectionsArgs here :(");
            }
            db.execSQL("UPDATE " + update_table + " SET lft=lft-2 WHERE " +
                       selection + ";");
            break;
        case UPDATE_LIST_MOVE_UP:
            if (selectionArgs != null && selectionArgs.length > 0) {
                throw new IllegalArgumentException("Sorry, but we cannot use selectionsArgs here :(");
            }
            db.execSQL("UPDATE " + update_table + " SET lft=lft+2 WHERE " +
                       selection + ";");
            break;
        case UPDATE_LIST_FIX_RGT:
            db.execSQL ("UPDATE " + update_table + " SET rgt=lft+1;");
            break;
        default:
            u = db.update(table, values, selection, selectionArgs);
        }
        if (!locked) {
            db.setTransactionSuccessful();
            db.endTransaction();
        }
        return u;
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    public static void withTransaction(final DBTransaction what) {
        if (what != null) {
            final SQLiteDatabase db = getWritableDatabase();
            if (!db.inTransaction()) {
                db.beginTransaction();
                try {
                    what.exec();
                    db.setTransactionSuccessful();
                } catch (final Exception e) {
                    Log.w(TAG,
                          "an exception was raised while executing database transaction",
                          e);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                        throw new android.database.SQLException(
                            "General error while executing witTransaction",
                            e);
                    } else {
                        throw new android.database.SQLException(
                            "General error while executing witTransaction");
                    }
                } finally {
                    db.endTransaction();
                }
            } else {
                if (!isPreInit) {
                    throw new DataBaseLockedException(
                        "Database already in a transaction");
                }
            }
        }
    }

}
