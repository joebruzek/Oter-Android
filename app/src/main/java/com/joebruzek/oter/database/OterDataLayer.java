package com.joebruzek.oter.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.provider.ContactsContract;
import android.util.Log;

import com.joebruzek.oter.models.Location;
import com.joebruzek.oter.models.Oter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * OterDataLayer is an abstraction of the Oter table in the SQLiteDatabase.
 * Provides functions for interacting with the oter data
 *
 * Created by jbruzek on 11/13/15.
 */
public class OterDataLayer {

    //large arbitrary oter limit
    private final static int OTER_LIMIT = 10000;

    private SQLiteDatabase database;
    private DatabaseAdapter adapter;
    private DatabaseListenerComposite listener;

    /**
     * Constructor. Initialize the DatabaseAdapter.
     *
     * @param context
     */
    public OterDataLayer(Context context) {
        adapter = DatabaseAdapter.getInstance(context);
        listener = DatabaseListenerComposite.getInstance();
    }

    /**
     * open the database for use
     */
    public void openDB() {
        database = adapter.getWritableDatabase();
    }

    /**
     * Close the database when we're done
     */
    public void closeDB() {
        adapter.close();
    }

    /**
     * Utility to compare two cursors
     * @param c1
     * @param c2
     * @return if the cursors point to data that is the same
     */
    public static boolean cursorsEqual(Cursor c1, Cursor c2) {
        if (c1 == null || c2 == null) {
            return false;
        }

        c1.moveToFirst();
        c2.moveToFirst();

        String[] columns = c1.getColumnNames();
        //check to see if the column names are equal
        if (!Arrays.equals(columns, c2.getColumnNames())
                || c1.getCount() != c2.getCount()) {
            return false;
        }

        //check the values of each column
        for (int i = 0; i < columns.length; i++) {
            if (c1.getType(i)
                    != c2.getType(i)) {
                return false;
            }
            switch (c1.getType(i)) {
                case Cursor.FIELD_TYPE_STRING:
                    if (!c1.getString(i).equals(c2.getString(i))) {
                        return false;
                    }
                    break;
                case Cursor.FIELD_TYPE_FLOAT:
                    if (c1.getFloat(i) != c2.getFloat(i)) {
                        return false;
                    }
                    break;
                case Cursor.FIELD_TYPE_INTEGER:
                    if (c1.getInt(i) != c2.getInt(i)) {
                        return false;
                    }
                    break;
                case Cursor.FIELD_TYPE_BLOB:
                    if (!Arrays.equals(c1.getBlob(i), c2.getBlob(i))) {
                        return false;
                    }
                    break;
                default:
            }
        }
        //they truly are the same
        return true;
    }

    /**
     * insert a oter into the database
     * First we need to insert a location so we have the foreign key reference
     *
     * Also set the id of the oter passes as parameter to the primaryKey of the Oter in the db
     *
     * @param oter
     * @return the oter primary key
     */
    public long insertOter(Oter oter) {
        //Check to see if the location is already in the database
        long locationId = insertLocationIfNotExists(oter.getLocation());

        //insert the oter into the database
        ContentValues values = new ContentValues();
        values.put(DatabaseContract.OtersContract.KEY_MESSAGE, oter.getMessage());
        values.put(DatabaseContract.OtersContract.KEY_LOCATION, locationId);
        values.put(DatabaseContract.OtersContract.KEY_TIME, oter.getTime());
        oter.setId(database.insert(DatabaseContract.OtersContract.TABLE_NAME, null, values));

        //insert the contacts now that we have the oter inserted
        insertAllContacts(oter.getId(), oter.getContacts());

        //notify the listeners
        listener.onItemInserted(DatabaseContract.OtersContract.TABLE_NAME, oter.getId());
        return oter.getId();
    }

    /**
     * insert a Location into the database
     *
     * Also set the id of the location set as a parameter to the primary key of the Location in the db
     *
     * @param l the location
     * @return the location primary key
     */
    public long insertLocation(Location l) {
        ContentValues values = new ContentValues();
        values.put(DatabaseContract.LocationsContract.KEY_NAME, l.getName());
        values.put(DatabaseContract.LocationsContract.KEY_NICKNAME, l.getNickname());
        values.put(DatabaseContract.LocationsContract.KEY_ADDRESS, l.getAddress());
        values.put(DatabaseContract.LocationsContract.KEY_LONGITUDE, l.getLongitude());
        values.put(DatabaseContract.LocationsContract.KEY_LATITUDE, l.getLatitude());
        l.setId(database.insert(DatabaseContract.LocationsContract.TABLE_NAME, null, values));

        //notify the listeners
        listener.onItemInserted(DatabaseContract.LocationsContract.TABLE_NAME, l.getId());
        return l.getId();
    }

    /**
     * Insert a location if it doesn't already exist in the database.
     * @param l
     * @return the id of the location
     */
    private long insertLocationIfNotExists(Location l) {
        Location l2 = getLocationIfExists(l);
        if (l2 == null) {
            return insertLocation(l);
        } else {
            return l2.getId();
        }
    }

    /**
     * Delete an Oter from the database
     * @param oter
     * @return how many rows were deleted. Should only be on, since we're deleting a specific oter
     */
    public int removeOter(Oter oter) {
        return removeOter(oter.getId());
    }

    /**
     * Delete an oter from the database using the id of the oter
     * @param id
     * @return how many rows were deleted. Should only be one, since we're deleting a specific oter
     */
    private int removeOter(long id) {
        String whereClause = DatabaseContract.OtersContract.KEY_ID + " = ?";
        String[] whereArgs = {String.valueOf(id)};
        int value = database.delete(DatabaseContract.OtersContract.TABLE_NAME,
                whereClause,
                whereArgs);
        listener.onItemDeleted(DatabaseContract.OtersContract.TABLE_NAME, id);

        //delete the relations with this oter
        removeContactRelations(id);

        return value;
    }

    /**
     * Update an oter in the database with the information of this oter.
     * @param oter
     * @return the number of affected rows, should be one, since we're querying by id
     */
    public int updateOter(Oter oter) {
        //check to see if the new location exists in the database
        long locationId = insertLocationIfNotExists(oter.getLocation());

        ContentValues values = new ContentValues();
        values.put(DatabaseContract.OtersContract.KEY_MESSAGE, oter.getMessage());
        values.put(DatabaseContract.OtersContract.KEY_LOCATION, locationId);
        values.put(DatabaseContract.OtersContract.KEY_TIME, oter.getTime());

        String selection = DatabaseContract.OtersContract.KEY_ID + " = ?";
        String[] selectionArgs = {String.valueOf(oter.getId())};

        int count = database.update(DatabaseContract.OtersContract.TABLE_NAME,
                values,
                selection,
                selectionArgs);

        //update the contact relations of this oter
        updateContactRelations(oter);

        //update the listeners
        listener.onItemUpdated(DatabaseContract.OtersContract.TABLE_NAME, oter.getId());
        return count;
    }

    /**
     * get an oter from an id
     * @param id
     * @return
     */
    public Oter getOter(long id) {
        String whereClause = DatabaseContract.OtersContract.KEY_ID + " = " + id;
        Cursor cursor = database.query(
                DatabaseContract.OtersContract.TABLE_NAME,
                DatabaseContract.OtersContract.ALL_COLUMNS,
                whereClause,
                null, null, null, null,
                "1");
        if(cursor.getCount() <= 0){
            cursor.close();
            return null;
        }
        if (cursor.moveToFirst()) {
            Oter o = buildOter(cursor);
            cursor.close();
            return o;
        }
        cursor.close();
        return null;
    }

    /**
     * Get a cursor for the database result of querying all oters
     *
     * @param limit the limit for how many oters you want to receive
     * @return
     */
    public Cursor getAllOtersCursor(int limit) {
        String orderBy = DatabaseContract.OtersContract.KEY_ID + " DESC";
        return database.query(
                DatabaseContract.OtersContract.TABLE_NAME,
                DatabaseContract.OtersContract.ALL_COLUMNS,
                null, null, null, null,
                orderBy,
                String.valueOf(limit));
    }

    /**
     * Get all oters without specifying a limit
     * @return
     */
    public Cursor getAllOtersCursor() {
        return getAllOtersCursor(OTER_LIMIT);
    }

    /**
     * Get a list of all the oters in the database
     *
     * @param limit the limit for how many oters you want to retrieve.
     * @return a list of oters.
     */
    public List<Oter> getAllOters(int limit) {
        List<Oter> oters = new ArrayList<Oter>();

        Cursor cursor = getAllOtersCursor(limit);

        if (cursor != null && cursor.moveToFirst()) {
            while (cursor.moveToNext()) {
                oters.add(buildOter(cursor));
            }
        }
        cursor.close();
        return oters;
    }

    /**
     * Get a list of oters without specifying a limit
     * @return
     */
    public List<Oter> getAllOters() {
        return getAllOters(OTER_LIMIT);
    }

    /**
     * Return whether or not the location is in the database
     * @param l
     * @return the location if it exists, null else
     */
    public Location getLocationIfExists(Location l) {
        String whereClause = DatabaseContract.LocationsContract.KEY_NAME + " = ? AND " +
            DatabaseContract.LocationsContract.KEY_NICKNAME + " = ? AND " +
            DatabaseContract.LocationsContract.KEY_ADDRESS + " = ? AND " +
            DatabaseContract.LocationsContract.KEY_LONGITUDE + " = " + l.getLongitude() + " AND " +
            DatabaseContract.LocationsContract.KEY_LATITUDE + " = " + l.getLatitude();
        String[] whereArgs = new String[] {l.getName(), l.getAddress()};
        String orderBy = DatabaseContract.OtersContract.KEY_ID + " DESC";
        Cursor cursor = database.query(
                DatabaseContract.LocationsContract.TABLE_NAME,
                DatabaseContract.LocationsContract.ALL_COLUMNS,
                whereClause,
                whereArgs,
                null, null,
                orderBy,
                "1");
        if(cursor.getCount() <= 0){
            cursor.close();
            return null;
        }
        if (cursor.moveToFirst()) {
            //TODO: Should this just return the parameter?
            Location loc = buildLocation(cursor);
            cursor.close();
            return loc;
        }
        cursor.close();
        return new Location();
    }

    /**
     * Get a location from an id
     * @param id
     * @return the location if it exists, null else
     */
    public Location getLocationFromId(long id) {
        String whereClause = DatabaseContract.LocationsContract.KEY_ID + " = " + id;
        String orderBy = DatabaseContract.LocationsContract.KEY_ID + " DESC";
        Cursor cursor = database.query(
                DatabaseContract.LocationsContract.TABLE_NAME,
                DatabaseContract.LocationsContract.ALL_COLUMNS,
                whereClause,
                null, null, null,
                orderBy,
                "1");
        if(cursor.getCount() <= 0){
            cursor.close();
            return null;
        }
        Location loc = null;
        if (cursor.moveToFirst()) {
            loc = buildLocation(cursor);
        }
        cursor.close();
        return loc;
    }

    /**
     * Get a oter object from a cursor.
     *
     * IMPORTANT - this is the same cursor that may be iterating over a larger list of oters.
     * The cursor should not be manipulated in this method, only get values from it in its current position
     *
     * @param cursor
     * @return
     */
    public Oter buildOter(Cursor cursor) {
        Oter oter = new Oter();
        oter.setId(cursor.getLong(cursor.getColumnIndex(DatabaseContract.OtersContract.KEY_ID)));
        oter.setMessage(cursor.getString(cursor.getColumnIndex(DatabaseContract.OtersContract.KEY_MESSAGE)));
        oter.setTime(cursor.getInt(cursor.getColumnIndex(DatabaseContract.OtersContract.KEY_TIME)));
        oter.setLocation(getLocationFromId(cursor.getLong(cursor.getColumnIndex(DatabaseContract.OtersContract.KEY_LOCATION))));
        oter.setContacts(getContacts(oter.getId()));
        return oter;
    }

    /**
     * Get a Location object from a cursor.
     *
     * IMPORTANT - this is the same cursor that may be iterating over a larger list of locations.
     * The cursor should not be manipulated in this method, only get values from it in its current position
     *
     * @param cursor
     * @return
     */
    public Location buildLocation(Cursor cursor) {
        Location l = new Location();
        l.setId(cursor.getLong(cursor.getColumnIndex(DatabaseContract.LocationsContract.KEY_ID)));
        l.setName(cursor.getString(cursor.getColumnIndex(DatabaseContract.LocationsContract.KEY_NAME)));
        l.setNickname(cursor.getString(cursor.getColumnIndex(DatabaseContract.LocationsContract.KEY_NICKNAME)));
        l.setAddress(cursor.getString(cursor.getColumnIndex(DatabaseContract.LocationsContract.KEY_ADDRESS)));
        l.setLatitude(cursor.getDouble(cursor.getColumnIndex(DatabaseContract.LocationsContract.KEY_LATITUDE)));
        l.setLongitude(cursor.getDouble(cursor.getColumnIndex(DatabaseContract.LocationsContract.KEY_LONGITUDE)));
        return l;
    }

    /**
     * Get a cursor for the database result of querying all active oters
     *
     * @return a cursor to the query result
     */
    public Cursor getActiveOtersCursor() {
        String whereClause = "active = ?";
        String[] whereArgs = new String[] {"true"};
        String orderBy = DatabaseContract.OtersContract.KEY_ID + " DESC";
        return database.query(
                DatabaseContract.OtersContract.TABLE_NAME,
                DatabaseContract.OtersContract.ALL_COLUMNS,
                whereClause,
                whereArgs,
                null, null,
                orderBy);
    }


    /**
     * get all of the oters that are "active"
     */
    public List<Oter> getActiveOters() {
        List<Oter> oters = new ArrayList<Oter>();

        Cursor cursor = getActiveOtersCursor();

        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            oters.add(buildOter(cursor));
            cursor.moveToNext();
        }
        cursor.close();
        return oters;
    }

    /**
     * Insert a contact into the database. If it already exists, then return the id of the existing one
     * @param phoneNumber
     * @return the id (primaryKey) of the inserted number
     */
    public long insertContact(String phoneNumber) {
        long id = getContactIfExists(phoneNumber);
        if (id == -1) {
            return insertNewContact(phoneNumber);
        }
        return id;
    }

    /**
     * Insert a number into the database
     * @param phoneNumber
     * @return the id (primaryKey)
     */
    private long insertNewContact(String phoneNumber) {
        ContentValues values = new ContentValues();
        values.put(DatabaseContract.ContactsContract.KEY_NUMBER, phoneNumber);
        long id = database.insert(DatabaseContract.ContactsContract.TABLE_NAME, null, values);

        //notify the listeners
        listener.onItemInserted(DatabaseContract.ContactsContract.TABLE_NAME, id);
        return id;
    }

    /**
     * Check to see if a phone number exists in the database
     * @param phoneNumber
     * @return the primarykey of the db column, -1 if the number doesn't exist in the database
     */
    private long getContactIfExists(String phoneNumber) {
        String[] columns = {DatabaseContract.ContactsContract.KEY_ID};
        String whereClause = DatabaseContract.ContactsContract.KEY_NUMBER + " = ?";
        String[] whereArgs = {phoneNumber};
        String orderBy = DatabaseContract.ContactsContract.KEY_ID + " DESC";
        Cursor cursor = database.query(
                DatabaseContract.ContactsContract.TABLE_NAME,
                columns,
                whereClause,
                whereArgs,
                null, null,
                orderBy,
                "1");
        if(cursor.getCount() <= 0){
            cursor.close();
            return -1;
        }
        if (cursor.moveToFirst()) {
            return cursor.getLong(cursor.getColumnIndex(DatabaseContract.ContactsContract.KEY_ID));
        }
        cursor.close();
        return -1;
    }

    /**
     * Insert all of the contacts in a list. Create relations to the oter if they don't already exist
     * @param oterId
     * @param phoneNumbers
     */
    private void insertAllContacts(long oterId, ArrayList<String> phoneNumbers) {
        //insert all contacts
        for (String number : phoneNumbers) {
            long cid = insertContact(number);
            insertContactRelation(oterId, cid);
        }
    }

    /**
     * Insert a contact relation depending on whether or not it already exists
     * @param oterId
     * @param contactId
     */
    private void insertContactRelation(long oterId, long contactId) {
        if (!contactRelationExists(oterId, contactId)) {
            insertNewContactRelation(oterId, contactId);
        }
    }

    /**
     * Insert a contact relation to the database
     * @param oterId
     * @param contactId
     */
    private void insertNewContactRelation(long oterId, long contactId) {
        ContentValues values = new ContentValues();
        values.put(DatabaseContract.ContactRelationContract.KEY_OTER, oterId);
        values.put(DatabaseContract.ContactRelationContract.KEY_CONTACT, contactId);
        database.insert(DatabaseContract.ContactRelationContract.TABLE_NAME, null, values);

        //notify the listeners
        listener.onItemInserted(DatabaseContract.ContactRelationContract.TABLE_NAME, 0);
    }

    /**
     * Check to see if a contact relation already exists in the database
     * @param oterId
     * @param contactId
     * @return
     */
    private boolean contactRelationExists(long oterId, long contactId) {
        String whereClause = DatabaseContract.ContactRelationContract.KEY_OTER + " = " + oterId +
                " AND " + DatabaseContract.ContactRelationContract.KEY_CONTACT + " = " + contactId;
        String orderBy = DatabaseContract.ContactRelationContract.KEY_CONTACT + " DESC";
        Cursor cursor = database.query(
                DatabaseContract.ContactRelationContract.TABLE_NAME,
                null,
                whereClause,
                null, null, null,
                orderBy,
                "1");
        if(cursor.getCount() <= 0){
            cursor.close();
            return false;
        }
        cursor.close();
        return true;
    }

    /**
     * Delete all of the contact relations that are related to this oter
     * @param oterId
     */
    private void removeContactRelations(long oterId) {
        String whereClause = DatabaseContract.ContactRelationContract.KEY_OTER + " = ?";
        String[] whereArgs = {String.valueOf(oterId)};
        int value = database.delete(DatabaseContract.ContactRelationContract.TABLE_NAME,
                whereClause,
                whereArgs);
        listener.onItemDeleted(DatabaseContract.ContactRelationContract.TABLE_NAME, value);
    }

    /**
     * Update the contact relations for this oter
     * Since we're not expecting an oter to have more than a handful of contacts at a time
     * (certainly not hundreds or thousands) it is acceptable to simply remove and reinsert contactRelations
     *
     * @param oter
     */
    private void updateContactRelations(Oter oter) {
        removeContactRelations(oter.getId());
        insertAllContacts(oter.getId(), oter.getContacts());
        listener.onItemUpdated(DatabaseContract.ContactRelationContract.TABLE_NAME, 0);
    }

    /**
     * Get a list of phone numbers from an oter id
     * @param oterId
     * @return
     */
    public ArrayList<String> getContacts(long oterId) {
        return buildContacts(getContactsCursor(oterId));
    }

    /**
     * Get a cursor to all of the contacts that are associated with this oter id
     * @param oterId
     * @return
     */
    private Cursor getContactsCursor(long oterId) {
        String query = "SELECT * FROM " + DatabaseContract.ContactsContract.TABLE_NAME + " " +
                "JOIN " + DatabaseContract.ContactRelationContract.TABLE_NAME + " " +
                "ON " + DatabaseContract.ContactRelationContract.KEY_CONTACT + " " +
                "= " + DatabaseContract.ContactsContract.KEY_ID + " " +
                "WHERE " + DatabaseContract.ContactRelationContract.KEY_OTER +  " " +
                "= ?";
        String[] args = {String.valueOf(oterId)};
        return database.rawQuery(query, args);
    }

    /**
     * Build a list of numbers from a contact query
     * @param c
     * @return
     */
    private ArrayList<String> buildContacts(Cursor c) {
        ArrayList<String> result = new ArrayList<String>();
        c.moveToFirst();
        while (!c.isAfterLast()) {
            result.add(c.getString(c.getColumnIndex(DatabaseContract.ContactsContract.KEY_NUMBER)));
            c.moveToNext();
        }
        return result;
    }
}