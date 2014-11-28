package com.couchbase.lite;


import com.couchbase.lite.cbforest.OpenFlags;
import com.couchbase.lite.cbforest.RevIDBuffer;
import com.couchbase.lite.cbforest.Slice;
import com.couchbase.lite.cbforest.Transaction;
import com.couchbase.lite.cbforest.VersionedDocument;
import com.couchbase.lite.internal.AttachmentInternal;
import com.couchbase.lite.internal.InterfaceAudience;
import com.couchbase.lite.internal.RevisionInternal;
import com.couchbase.lite.replicator.Replication;
import com.couchbase.lite.storage.SQLException;
import com.couchbase.lite.storage.SQLiteStorageEngine;
import com.couchbase.lite.support.FileDirUtils;
import com.couchbase.lite.support.HttpClientFactory;
import com.couchbase.lite.support.PersistentCookieStore;
import com.couchbase.lite.util.Log;
import com.couchbase.lite.util.Utils;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by hideki on 11/22/14.
 */
public class DatabaseCBForest implements Database {
    /** static constructor */
    static {
        try{
            System.loadLibrary("cbforest");
            Log.i("DatabaseCBForest", "load libcbforest OK !!!");
        }
        catch(Exception e){
            Log.e("DatabaseCBForest", "Failed to load libcbforest !!!");
        }
    }

    // Default value for maxRevTreeDepth, the max rev depth to preserve in a prune operation
    private static final int DEFAULT_MAX_REVS = Integer.MAX_VALUE;

    public final static String TAG ="DatabaseCBForest";

    private String path = null;
    private String name = null;
    private Manager manager = null;

    private boolean open = false;
    private int transactionLevel = 0;

    private Map<String, Validator> validations = null;

    final private CopyOnWriteArrayList<ChangeListener> changeListeners;
    private Cache<String, Document> docCache;
    private List<DocumentChange> changesToNotify;
    private boolean postingChangeNotifications = false;


    private int maxRevTreeDepth = DEFAULT_MAX_REVS;

    // CBForest
    com.couchbase.lite.cbforest.Database database;
    //com.couchbase.cbforest.Transaction transaction;
    Transaction forestTransaction;

    public DatabaseCBForest(String path, Manager manager) {
        assert(new File(path).isAbsolute()); //path must be absolute
        this.path = path;
        this.name = FileDirUtils.getDatabaseNameFromPath(path);
        this.manager = manager;
        this.changeListeners = new CopyOnWriteArrayList<ChangeListener>();
        this.docCache = new Cache<String, Document>();
        //this.startTime = System.currentTimeMillis();
        this.changesToNotify = new ArrayList<DocumentChange>();
        //this.activeReplicators =  Collections.newSetFromMap(new ConcurrentHashMap());
        //this.allReplicators = Collections.newSetFromMap(new ConcurrentHashMap());

    }
    public boolean open() {
        database = new com.couchbase.lite.cbforest.Database(path, OpenFlags.FDB_OPEN_FLAG_CREATE, com.couchbase.lite.cbforest.Database.defaultConfig());
        open = true;
        return open;
    }

    public boolean close() {
        if(!open) {
            return false;
        }

        if(database != null) {
            database.delete(); // <- release instance. not delete database
            database = null;
        }

        return true;
    }
    public String getName() {
        return name;
    }
    public String getPath() {
        return path;
    }

    public Manager getManager() {
        return manager;
    }

    public int getDocumentCount() {
        return 0;
    }

    public long getLastSequenceNumber() {
        return database.getLastSequence().longValue();
    }

    public List<Replication> getAllReplications() {
        return null;
    }

    public void compact() throws CouchbaseLiteException {
        database.compact();
    }

    // NOTE: Same with SQLite?
    public void delete() throws CouchbaseLiteException {
        // delete db file and index
        database.deleteDatabase();
    }

    // NOTE: Same with SQLite?
    public Document getDocument(String documentId) {
        if (documentId == null || documentId.length() == 0) {
            return null;
        }
        Document doc = docCache.get(documentId);
        if (doc == null) {
            doc = new Document(this, documentId);
            if (doc == null) {
                return null;
            }
            docCache.put(documentId, doc);
        }
        return doc;
    }

    public Document getExistingDocument(String documentId) {
        return null;
    }

    // NOTE: Same with SQLite?
    public Document createDocument() {
        return getDocument(Misc.TDCreateUUID());
    }

    public Map<String, Object> getExistingLocalDocument(String documentId) {
        return null;
    }

    public boolean putLocalDocument(String id, Map<String, Object> properties) throws CouchbaseLiteException {
        return false;
    }

    public boolean deleteLocalDocument(String id) throws CouchbaseLiteException {
        return false;
    }

    public Query createAllDocumentsQuery() {
        return null;
    }

    public View getView(String name) {
        return null;
    }

    public View getExistingView(String name) {
        return null;
    }

    public Validator getValidation(String name) {
        Validator result = null;
        if(validations != null) {
            result = validations.get(name);
        }
        return result;
    }

    public void setValidation(String name, Validator validator) {
        if(validations == null) {
            validations = new HashMap<String, Validator>();
        }
        if (validator != null) {
            validations.put(name, validator);
        }
        else {
            validations.remove(name);
        }
    }

    public ReplicationFilter getFilter(String filterName) {
        return null;
    }

    public void setFilter(String filterName, ReplicationFilter filter) {

    }

    public boolean runInTransaction(TransactionalTask transactionalTask) {
        return false;
    }

    public Future runAsync(AsyncTask asyncTask) {
        return null;
    }

    public Replication createPushReplication(URL remote) {
        return null;
    }

    public Replication createPullReplication(URL remote) {
        return null;
    }

    // same?
    @InterfaceAudience.Public
    public void addChangeListener(ChangeListener listener) {
        changeListeners.addIfAbsent(listener);
    }

    // same?
    @InterfaceAudience.Public
    public void removeChangeListener(ChangeListener listener) {
        changeListeners.remove(listener);
    }

    public int getMaxRevTreeDepth() {
        return maxRevTreeDepth;
    }

    public void setMaxRevTreeDepth(int maxRevTreeDepth) {
        this.maxRevTreeDepth = maxRevTreeDepth;
    }

    public Document getCachedDocument(String documentID) {
        return docCache.get(documentID);
    }

    public void clearDocumentCache() {
        docCache.clear();
    }

    public List<Replication> getActiveReplications() {
        return null;
    }

    // NOTE: Same with SQLite?
    public void removeDocumentFromCache(Document document) {
        docCache.remove(document.getId());
    }

    public boolean exists() {
        return false;
    }

    public String getAttachmentStorePath() {
        return null;
    }

    public boolean initialize(String statements) {
        return false;
    }



    public SQLiteStorageEngine getDatabase() {
        return null;
    }

    public BlobStore getAttachments() {
        return null;
    }

    public BlobStoreWriter getAttachmentWriter() {
        return null;
    }

    public long totalDataSize() {
        return 0;
    }

    public boolean beginTransaction() {
        // Transaction() -> db.beginTransaction()
        forestTransaction = new Transaction(database);
        transactionLevel++;
        Log.w(TAG, "%s Begin transaction (level %d)", Thread.currentThread().getName(), transactionLevel);
        return true;
    }

    public boolean endTransaction(boolean commit) {

        assert(transactionLevel > 0);
        if(commit) {
            Log.i(TAG, "%s Committing transaction (level %d)", Thread.currentThread().getName(), transactionLevel);
            // ~Transaction() -> db.endTransaction() -> fdb_end_transaction
        }
        else {
            Log.i(TAG, "%s CANCEL transaction (level %d)", Thread.currentThread().getName(), transactionLevel);
            // set state -> abort
            forestTransaction.abort();
            // ~Transaction() -> db.endTransaction() -> fdb_abort_transaction
        }
        forestTransaction.delete();
        forestTransaction = null;

        transactionLevel--;

        return true;
    }

    public String privateUUID() {
        return null;
    }

    public String publicUUID() {
        return null;
    }

    public byte[] appendDictToJSON(byte[] json, Map<String, Object> dict) {
        return new byte[0];
    }

    public Map<String, Object> extraPropertiesForRevision(RevisionInternal rev, EnumSet<TDContentOptions> contentOptions) {
        return null;
    }

    public void expandStoredJSONIntoRevisionWithAttachments(byte[] json, RevisionInternal rev, EnumSet<TDContentOptions> contentOptions) {

    }

    public Map<String, Object> documentPropertiesFromJSON(byte[] json, String docId, String revId, boolean deleted, long sequence, EnumSet<TDContentOptions> contentOptions) {
        return null;
    }

    /**
     * in CBLDatabase+Internal.m
     * - (CBL_Revision*) getDocumentWithID: (NSString*)docID
     *                          revisionID: (NSString*)inRevID
     *                             options: (CBLContentOptions)options
     *                              status: (CBLStatus*)outStatus
     */
    public RevisionInternal getDocumentWithIDAndRev(String docID, String inRevID, EnumSet<TDContentOptions> options) {
        RevisionInternal result = null;

        // TODO: add VersionDocument(Database, String)
        VersionedDocument doc = new VersionedDocument(database, new Slice(docID.getBytes()));
        if(!doc.exists()) {
            //throw new CouchbaseLiteException(Status.NOT_FOUND);
            return null;
        }

        String revID = inRevID;
        if(revID == null){
            com.couchbase.lite.cbforest.Revision rev = doc.currentRevision();
            if(rev == null || rev.isDeleted()) {
                //throw new CouchbaseLiteException(Status.DELETED);
                return null;
            }
            // TODO: add String getRevID()
            // TODO: revID is something wrong!!!!!
            //revID = rev.getRevID().getBuf();
            revID =  new String(rev.getRevID().expanded().getBuf());
        }

        result = ForestBridge.revisionObjectFromForestDoc(doc, revID, options);
        if(result == null)
            //throw new CouchbaseLiteException(Status.NOT_FOUND);
            return null;
        // TODO: Attachment support

        // TODO: need to release document?

        return result;
    }

    public boolean existsDocumentWithIDAndRev(String docId, String revId) {
        return false;
    }

    public RevisionInternal loadRevisionBody(RevisionInternal rev, EnumSet<TDContentOptions> contentOptions) throws CouchbaseLiteException {
        return null;
    }

    public long getDocNumericID(String docId) {
        return 0;
    }

    public RevisionList getAllRevisionsOfDocumentID(String docId, long docNumericID, boolean onlyCurrent) {
        return null;
    }

    public RevisionList getAllRevisionsOfDocumentID(String docId, boolean onlyCurrent) {
        return null;
    }

    public List<String> getConflictingRevisionIDsOfDocID(String docID) {
        return null;
    }

    public List<String> getPossibleAncestorRevisionIDs(RevisionInternal rev, int limit, AtomicBoolean hasAttachment) {
        return null;
    }

    public String findCommonAncestorOf(RevisionInternal rev, List<String> revIDs) {
        return null;
    }

    public List<RevisionInternal> getRevisionHistory(RevisionInternal rev) {
        return null;
    }

    public Map<String, Object> getRevisionHistoryDict(RevisionInternal rev) {
        return null;
    }

    public Map<String, Object> getRevisionHistoryDictStartingFromAnyAncestor(RevisionInternal rev, List<String> ancestorRevIDs) {
        return null;
    }

    public RevisionList changesSince(long lastSeq, ChangesOptions options, ReplicationFilter filter) {
        return null;
    }

    public boolean runFilter(ReplicationFilter filter, Map<String, Object> paramsIgnored, RevisionInternal rev) {
        return false;
    }

    public String getDesignDocFunction(String fnName, String key, List<String> outLanguageList) {
        return null;
    }

    public View registerView(View view) {
        return null;
    }

    public List<QueryRow> queryViewNamed(String viewName, QueryOptions options, List<Long> outLastSequence) throws CouchbaseLiteException {
        return null;
    }

    public View makeAnonymousView() {
        return null;
    }

    public List<View> getAllViews() {
        return null;
    }

    public Status deleteViewNamed(String name) {
        return null;
    }

    public int getDeletedColumnIndex(QueryOptions options) {
        return 0;
    }

    public Map<String, Object> getAllDocs(QueryOptions options) throws CouchbaseLiteException {
        return null;
    }

    public String winningRevIDOfDoc(long docNumericId, AtomicBoolean outIsDeleted, AtomicBoolean outIsConflict) throws CouchbaseLiteException {
        return null;
    }

    public void insertAttachmentForSequenceWithNameAndType(InputStream contentStream, long sequence, String name, String contentType, int revpos) throws CouchbaseLiteException {

    }

    public void insertAttachmentForSequenceWithNameAndType(long sequence, String name, String contentType, int revpos, BlobKey key) throws CouchbaseLiteException {

    }

    public void installAttachment(AttachmentInternal attachment, Map<String, Object> attachInfo) throws CouchbaseLiteException {

    }

    public void copyAttachmentNamedFromSequenceToSequence(String name, long fromSeq, long toSeq) throws CouchbaseLiteException {

    }

    public Attachment getAttachmentForSequence(long sequence, String filename) throws CouchbaseLiteException {
        return null;
    }

    public boolean sequenceHasAttachments(long sequence) {
        return false;
    }

    public Map<String, Object> getAttachmentsDictForSequenceWithContent(long sequence, EnumSet<TDContentOptions> contentOptions) {
        return null;
    }

    public URL fileForAttachmentDict(Map<String, Object> attachmentDict) {
        return null;
    }

    public void stubOutAttachmentsIn(RevisionInternal rev, int minRevPos) {

    }

    public boolean inlineFollowingAttachmentsIn(RevisionInternal rev) {
        return false;
    }

    public void processAttachmentsForRevision(Map<String, AttachmentInternal> attachments, RevisionInternal rev, long parentSequence) throws CouchbaseLiteException {

    }

    public RevisionInternal updateAttachment(String filename, BlobStoreWriter body, String contentType, AttachmentInternal.AttachmentEncoding encoding, String docID, String oldRevID) throws CouchbaseLiteException {
        return null;
    }

    public void rememberAttachmentWritersForDigests(Map<String, BlobStoreWriter> blobsByDigest) {

    }

    public void rememberAttachmentWriter(BlobStoreWriter writer) {

    }

    public Status garbageCollectAttachments() {
        return null;
    }

    public String generateIDForRevision(RevisionInternal rev, byte[] json, Map<String, AttachmentInternal> attachments, String previousRevisionId) {
        // NOTE: NOT IMPLEMENTED
        // see: generateRevIDForJSON()
        return null;
    }

    /**
     * Given an existing revision ID, generates an ID for the next revision.
     * Returns nil if prevID is invalid.
     */
    @InterfaceAudience.Private
    public String generateRevIDForJSON(byte[] json, boolean deleted, String previousRevisionId) {

        MessageDigest md5Digest;

        // Revision IDs have a generation count, a hyphen, and a UUID.

        int generation = 0;
        if(previousRevisionId != null) {
            generation = RevisionInternal.generationFromRevID(previousRevisionId);
            if(generation == 0) {
                return null;
            }
        }

        // Generate a digest for this revision based on the previous revision ID, document JSON,
        // and attachment digests. This doesn't need to be secure; we just need to ensure that this
        // code consistently generates the same ID given equivalent revisions.

        try {
            md5Digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        int length = 0;
        if (previousRevisionId != null) {
            byte[] prevIDUTF8 = previousRevisionId.getBytes(Charset.forName("UTF-8"));
            length = prevIDUTF8.length;
        }
        if (length > 0xFF) {
            return null;
        }
        byte lengthByte = (byte) (length & 0xFF);
        byte[] lengthBytes = new byte[] { lengthByte };

        md5Digest.update(lengthBytes);

        int isDeleted = ((deleted != false) ? 1 : 0);
        byte[] deletedByte = new byte[] { (byte) isDeleted };
        md5Digest.update(deletedByte);

        if (json != null) {
            md5Digest.update(json);
        }
        byte[] md5DigestResult = md5Digest.digest();

        String digestAsHex = Utils.bytesToHex(md5DigestResult);

        int generationIncremented = generation + 1;
        return String.format("%d-%s", generationIncremented, digestAsHex).toLowerCase();

    }

    public long insertDocumentID(String docId) {
        return 0;
    }

    public long getOrInsertDocNumericID(String docId) {
        return 0;
    }

    public byte[] encodeDocumentJSON(RevisionInternal rev) {
        return new byte[0];
    }

    public void notifyChange(RevisionInternal rev, RevisionInternal winningRev, URL source, boolean inConflict) {

    }

    public long insertRevision(RevisionInternal rev, long docNumericID, long parentSequence, boolean current, boolean hasAttachments, byte[] data) {
        return 0;
    }

    // NOTE: Same with SQLite?
    public RevisionInternal putRevision(RevisionInternal rev, String prevRevId, Status resultStatus) throws CouchbaseLiteException {
        return putRevision(rev, prevRevId, false, resultStatus);
    }

    // NOTE: Same with SQLite?
    public RevisionInternal putRevision(RevisionInternal rev, String prevRevId, boolean allowConflict) throws CouchbaseLiteException {
        Status ignoredStatus = new Status();
        return putRevision(rev, prevRevId, allowConflict, ignoredStatus);
    }

    public RevisionInternal putRevision(RevisionInternal putRev, String inPrevRevID, boolean allowConflict, Status outStatus) throws CouchbaseLiteException{
        return putDoc(putRev.getDocId(), putRev.getProperties(), inPrevRevID, allowConflict, outStatus);
    }

    /**
     * in CBLDatabase+Insertion.m  -
     * (CBL_Revision*)  putDocID: (NSString*)inDocID
     *                  properties: (NSMutableDictionary*)properties
     *                  prevRevisionID: (NSString*)inPrevRevID
     *                  allowConflict: (BOOL)allowConflict
     *                  status: (CBLStatus*)outStatus
     */
    public RevisionInternal putDoc(String inDocID, Map<String, Object> properties, String inPrevRevID, boolean allowConflict, Status resultStatus) throws CouchbaseLiteException {


        String docID = inDocID;
        String prevRevID = inPrevRevID;
        boolean deleting = false;
        if(properties == null || (properties.get("cbl_deleted") != null && properties.get("cbl_deleted") == Boolean.TRUE)){
            deleting = true;
        }

        Log.i(TAG, "PUT _id="+docID+", _rev="+prevRevID+", _deleted=" + deleting + ", allowConflict=" + allowConflict);

        if( (prevRevID != null && docID == null) ||
            (deleting && docID == null) ||
            (docID != null && !DatabaseUtil.isValidDocumentId(docID))){
            throw new CouchbaseLiteException(Status.BAD_REQUEST);
        }

        if(database.isReadOnly()){
            throw new CouchbaseLiteException(Status.FORBIDDEN);
        }

        RevisionInternal putRev = null;
        DocumentChange change = null;


        // TODO: Should be byte[] instead of String??
        String json = null;
        if(properties!=null){
            // TODO: Attachment

            // TODO: json = [CBL_Revision asCanonicalJSON: properties error: NULL];

            try {
                json = Manager.getObjectMapper().writeValueAsString(properties);
                if(json == null || json.isEmpty())
                    throw new CouchbaseLiteException(Status.BAD_JSON);
            } catch (Exception e) {
                throw new CouchbaseLiteException(Status.BAD_JSON);
            }
        }
        else{
            json = "{}";
        }


        Log.i(TAG, "json => " + json);

        beginTransaction();
        try{
            com.couchbase.lite.cbforest.Document rawDoc = new com.couchbase.lite.cbforest.Document();
            if(docID != null && !docID.isEmpty()){
                // Read the doc from the database:
                rawDoc.setKey(new Slice(docID.getBytes()));
                database.read(rawDoc);
            }
            else{
                // Create new doc ID, and don't bother to read it since it's a new doc:
                docID = Misc.TDCreateUUID();
                rawDoc.setKey(new Slice(docID.getBytes()));
            }

            // Parse the document revision tree:
            VersionedDocument doc = new VersionedDocument(database, rawDoc);
            com.couchbase.lite.cbforest.Revision revNode;

            if(inPrevRevID != null){
                // Updating an existing revision; make sure it exists and is a leaf:
                // TODO -> add VersionDocument.get(String revID)
                //      -> or Efficiently pass RevID to VersionDocument.get(RevID)
                //revNode = doc.get(new RevID(inPrevRevID));
                Log.w(TAG, "inPrevRevID => " + inPrevRevID);
                revNode = doc.get(new RevIDBuffer(new Slice(inPrevRevID.getBytes())));
                if(revNode == null)
                    throw new CouchbaseLiteException(Status.NOT_FOUND);
                else if(!allowConflict && !revNode.isLeaf())
                    throw new CouchbaseLiteException(Status.CONFLICT);
            }
            else{
                // No parent revision given:
                if(deleting){
                    // Didn't specify a revision to delete: NotFound or a Conflict, depending
                    if (doc.exists())
                        throw new CouchbaseLiteException(Status.CONFLICT);
                    else
                        throw new CouchbaseLiteException(Status.NOT_FOUND);
                }
                // If doc exists, current rev must be in a deleted state or there will be a conflict:
                revNode = doc.currentRevision();
                if(revNode != null){
                    if(revNode.isDeleted()) {
                        // New rev will be child of the tombstone:
                        // (T0D0: Write a horror novel called "Child Of The Tombstone"!)
                        prevRevID = new String(revNode.getRevID().getBuf());
                    }else {
                        throw new CouchbaseLiteException(Status.CONFLICT);
                    }
                }
            }

            boolean hasValidations = validations != null && validations.size() > 0;

            // Compute the new revID:
            String newRevID = generateRevIDForJSON(json.getBytes(), deleting, prevRevID);
            if(newRevID == null)
                throw new CouchbaseLiteException(Status.BAD_ID); // invalid previous revID (no numeric prefix)

            Log.w(TAG, "[putDoc()] newRevID => "+newRevID);

            putRev = new RevisionInternal(docID, newRevID, deleting);

            if(properties!=null){
                properties.put("_id", docID);
                properties.put("_rev", newRevID);
                putRev.setProperties(properties);
            }

            // Run any validation blocks:
            if(hasValidations){
                // TODO - implement!!!
            }

            // Add the revision to the database:
            int status;
            boolean isWinner;
            {
                // TODO - add new RevIDBuffer(String)
                // TODO - add RevTree.insert(String, String, boolean, boolean, RevID arg4, boolean)
                com.couchbase.lite.cbforest.Revision fdbRev = doc.insert(new RevIDBuffer(new Slice(newRevID.getBytes())),
                        new Slice(json.getBytes()),
                        deleting,
                        (putRev.getAttachments() != null),
                        revNode,
                        allowConflict);
                status = doc.getLatestHttpStatus();
                if(fdbRev!=null)
                    putRev.setSequence(fdbRev.getSequence().longValue());
                // TODO - implement status check code
                // TODO - is address compare good enough??
                isWinner = fdbRev.isSameAddress(doc.currentRevision());
            }

            // prune call will invalidate fdbRev ptr, so let it go out of scope

            doc.prune(maxRevTreeDepth);
            doc.save(forestTransaction);

            // TODO - implement doc.dump()

            // TODO - !!!! change With new Revision !!!!!

            // Success!
            if(deleting) {
                resultStatus.setCode(Status.OK);
            }
            else {
                resultStatus.setCode(Status.CREATED);
            }
        }
        finally {
            endTransaction(resultStatus.isSuccessful());
        }

        // TODO - status check

        // TODO - logging

        // Epilogue: A change notification is sent:
        if(change!=null)
            notifyChange(change);

        return putRev;
    }

    public void forceInsert(RevisionInternal rev, List<String> revHistory, URL source) throws CouchbaseLiteException {

    }

    public void validateRevision(RevisionInternal newRev, RevisionInternal oldRev, String parentRevID) throws CouchbaseLiteException {

    }

    public Replication getActiveReplicator(URL remote, boolean push) {
        return null;
    }

    public Replication getReplicator(URL remote, boolean push, boolean continuous, ScheduledExecutorService workExecutor) {
        return null;
    }

    public Replication getReplicator(String sessionId) {
        return null;
    }

    public Replication getReplicator(URL remote, HttpClientFactory httpClientFactory, boolean push, boolean continuous, ScheduledExecutorService workExecutor) {
        return null;
    }

    public String lastSequenceWithCheckpointId(String checkpointId) {
        return null;
    }

    public boolean setLastSequence(String lastSequence, String checkpointId, boolean push) {
        return false;
    }

    public String getLastSequenceStored(String checkpointId, boolean push) {
        return null;
    }

    public int findMissingRevisions(RevisionList touchRevs) throws SQLException {
        return 0;
    }

    public RevisionInternal putLocalRevision(RevisionInternal revision, String prevRevID) throws CouchbaseLiteException {
        return null;
    }

    public Query slowQuery(Mapper map) {
        return null;
    }

    public RevisionInternal getParentRevision(RevisionInternal rev) {
        return null;
    }

    public Map<String, Object> purgeRevisions(Map<String, List<String>> docsToRevs) {
        return null;
    }

    public boolean replaceUUIDs() {
        return false;
    }

    public RevisionInternal getLocalDocument(String docID, String revID) {
        return null;
    }

    public long getStartTime() {
        return 0;
    }

    public void deleteLocalDocument(String docID, String revID) throws CouchbaseLiteException {

    }

    public void setName(String name) {

    }

    // TODO not used for Forestdb
    public int pruneRevsToMaxDepth(int maxDepth) throws CouchbaseLiteException {
        return 0;
    }

    public boolean isOpen() {
        return false;
    }

    public void addReplication(Replication replication) {

    }

    public void forgetReplication(Replication replication) {

    }

    public void addActiveReplication(Replication replication) {

    }

    public PersistentCookieStore getPersistentCookieStore() {
        return null;
    }




    // SAME
    @InterfaceAudience.Private
    private void postChangeNotifications() {
        // This is a 'while' instead of an 'if' because when we finish posting notifications, there
        // might be new ones that have arrived as a result of notification handlers making document
        // changes of their own (the replicator manager will do this.) So we need to check again.
        while (transactionLevel == 0 && isOpen() && !postingChangeNotifications
                && changesToNotify.size() > 0)
        {

            try {
                postingChangeNotifications = true; // Disallow re-entrant calls

                List<DocumentChange> outgoingChanges = new ArrayList<DocumentChange>();
                outgoingChanges.addAll(changesToNotify);
                changesToNotify.clear();

                // TODO: change this to match iOS and call cachedDocumentWithID
                /*
                BOOL external = NO;
                for (CBLDatabaseChange* change in changes) {
                    // Notify the corresponding instantiated CBLDocument object (if any):
                    [[self _cachedDocumentWithID: change.documentID] revisionAdded: change];
                    if (change.source != nil)
                        external = YES;
                }
                */

                boolean isExternal = false;
                for (DocumentChange change: outgoingChanges) {
                    Document document = getDocument(change.getDocumentId());
                    document.revisionAdded(change);
                    if (change.getSourceUrl() != null) {
                        isExternal = true;
                    }
                }

                ChangeEvent changeEvent = new ChangeEvent(this, isExternal, outgoingChanges);

                for (ChangeListener changeListener : changeListeners) {
                    changeListener.changed(changeEvent);
                }

            } catch (Exception e) {
                Log.e(Database.TAG, this + " got exception posting change notifications", e);
            } finally {
                postingChangeNotifications = false;
            }

        }


    }
    // SAME
    private void notifyChange(DocumentChange documentChange) {
        if (changesToNotify == null) {
            changesToNotify = new ArrayList<DocumentChange>();
        }
        changesToNotify.add(documentChange);

        postChangeNotifications();
    }
}
