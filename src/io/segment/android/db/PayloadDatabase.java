package io.segment.android.db;

import io.segment.android.Analytics;
import io.segment.android.Constants;
import io.segment.android.models.BasePayload;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.util.Log;
import android.util.Pair;

public class PayloadDatabase extends SQLiteOpenHelper {

	private static final String TAG = SQLiteOpenHelper.class.getName();
	
	//
	// Singleton
	//
	
	private static PayloadDatabase instance;
	
	public static PayloadDatabase getInstance(Context context) {
		if (instance == null) {
			instance = new PayloadDatabase(context);
		}
		
		return instance;
	}
	
	//
	// Instance 
	//
	
	/**
	 * Caches the count of the database without requiring SQL count to be
	 * called every time. This will allow us to quickly determine whether
	 * our database is full and we shouldn't add anymore
	 */
	private AtomicLong count;
	private boolean initialCount;
	
	private IPayloadSerializer serializer = new JsonPayloadSerializer();
	
	private PayloadDatabase(Context context) {
		super(context, Constants.Database.NAME, null,
				Constants.Database.VERSION);
		
		this.count = new AtomicLong();
	}
	
	@Override
	public void onCreate(SQLiteDatabase db) {
		
		String sql = String.format("CREATE TABLE IF NOT EXISTS %s (%s %s, %s %s);",
				Constants.Database.PayloadTable.NAME,
				
				Constants.Database.PayloadTable.Fields.Id.NAME,
				Constants.Database.PayloadTable.Fields.Id.TYPE,
				
				Constants.Database.PayloadTable.Fields.Payload.NAME,
				Constants.Database.PayloadTable.Fields.Payload.TYPE);
		try {
			db.execSQL(sql);
		} catch (SQLException e) {
			Log.e(TAG, "Failed to create Segment.io SQL lite database: " + 
						Log.getStackTraceString(e));
		}
	}
	
	@Override
	public void onOpen(SQLiteDatabase db) {
		super.onOpen(db);
	}

	/**
	 * Counts the size of the current database and sets the cached counter
	 * 
	 * This shouldn't be called onOpen() or onCreate() because it will cause
	 * a recursive database get.
	 */
	private void ensureCount() {
		if (!initialCount) {
			count.set(countRows());
			initialCount = true;
		}
	}
	
	@Override
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
		// do nothing here
	}

	/**
	 * Adds a payload to the database
	 * @param payload 
	 */
	public boolean addPayload(BasePayload payload) {
		
		ensureCount();
		
		long rowCount = getRowCount();
		if (rowCount >= Analytics.getOptions().getMaxQueueSize()) {
			Log.w(TAG, "Cant add action, the database is larger than max queue size.");
			return false;
		}
		
		boolean success = false;
		
		long start = System.currentTimeMillis();
		
		String json = serializer.serialize(payload);
		
		long serializationDuration = System.currentTimeMillis() - start;
		
		start = System.currentTimeMillis();
		
		synchronized (this) {
			
			long lockDuration = System.currentTimeMillis() - start;
			
			SQLiteDatabase db = null;
			
			try {
				
				db = getWritableDatabase();
				ContentValues contentValues = new ContentValues();
				
				contentValues.put(
						Constants.Database.PayloadTable.Fields.Payload.NAME, 
						json);
				
				long result = db.insert(Constants.Database.PayloadTable.NAME, null,
						contentValues);
				
				if (result == -1) {
					Log.w(TAG, "Database insert failed. Result: " + result);
				} else {
					success = true;
					// increase the row count
					count.addAndGet(1);
				}
				
			} catch (SQLiteException e) {
				
				Log.e(TAG, "Failed to open or write to Segment.io payload db: " + 
						Log.getStackTraceString(e));
				
			}
			
			long insertDuration = System.currentTimeMillis() - start;
			
			Log.e(TAG, "Serialization : "  + serializationDuration + " , lock : " + lockDuration + " , insert : " + insertDuration);
			
			return success;
		}
	}

	/**
	 * Fetches the total amount of rows in the database
	 * @return
	 */
	private long countRows() {
		
		String sql = String.format("SELECT COUNT(*) FROM %s", 
				Constants.Database.PayloadTable.NAME);
		
		SQLiteDatabase db = getWritableDatabase();
		SQLiteStatement statement = db.compileStatement(sql);
		long numberRows = statement.simpleQueryForLong();
		
		return numberRows;
	}
	
	/**
	 * Fetches the total amount of rows in the database without
	 * an actual database query, using a cached counter.
	 * @return
	 */
	public long getRowCount() {
		if (!initialCount) ensureCount();
		return count.get();
	}

	/**
	 * Get the next (limit) events from the database
	 * @param limit
	 * @return
	 */
	public List<Pair<Long, BasePayload>> getEvents(int limit) {

		List<Pair<Long, BasePayload>> result = 
				new LinkedList<Pair<Long, BasePayload>>();
		
		SQLiteDatabase db = null;
		Cursor cursor = null;
		
		try {
		
			db = getWritableDatabase();
			
			String table = Constants.Database.PayloadTable.NAME;
			String[] columns = Constants.Database.PayloadTable.FIELD_NAMES;
			String selection = null;
			String selectionArgs[] = null;
			String groupBy = null;
			String having = null;
			String orderBy = Constants.Database.PayloadTable.Fields.Id.NAME + " ASC";
			String limitBy = "" + limit;
			
			cursor = db.query(table, columns, selection, selectionArgs, 
					groupBy, having, orderBy, limitBy);
		
			while (cursor.moveToNext()) {
				long id = cursor.getLong(0);
				String json = cursor.getString(1);
	
				BasePayload payload = serializer.deseralize(json);
				
				if (payload != null) 
					result.add(new Pair<Long, BasePayload>(id, payload));
			}
		} catch (SQLiteException e) {

			Log.e(TAG, "Failed to open or read from the Segment.io payload db: " + 
					Log.getStackTraceString(e));
			
		} finally {
			if (cursor != null) cursor.close();
		}
	
		return result;
	}

	/**
	 * Remove these events from the database
	 * @param minId
	 * @param maxId
	 */
	@SuppressLint("DefaultLocale")
	public int removeEvents(long minId, long maxId) {

		ensureCount();
		
		SQLiteDatabase db = null;

		String ID_FIELD_NAME = Constants.Database.PayloadTable.Fields.Id.NAME;
		
		String filter = String.format("%s >= %d AND %s <= %d",  
				ID_FIELD_NAME, minId, ID_FIELD_NAME, maxId);
				
		int deleted = -1;
		
		try {
			db = getWritableDatabase();
			deleted = db.delete(Constants.Database.PayloadTable.NAME, filter, null);
			
			// decrement the row counter
			count.addAndGet(-deleted);
			
		} catch (SQLiteException e) {

			Log.e(TAG, "Failed to remove items from the Segment.io payload db: " + 
					Log.getStackTraceString(e));
			
		}
		
		return deleted;
	}
	
}