/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package lucene.security.index;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import lucene.security.DocumentAuthorizations;
import lucene.security.DocumentVisibility;
import lucene.security.DocumentVisibilityEvaluator;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.AtomicReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.util.BytesRef;

import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;

public class DocValueAccessLookup extends AccessLookup {

  private final DocumentAuthorizations _readUnionDiscoverAuthorizations;
  private final DocumentAuthorizations _readAuthorizations;
  private final String _readField;
  private final String _discoverField;
  private final DocumentVisibilityEvaluator _readUnionDiscoverVisibilityEvaluator;
  private final DocumentVisibilityEvaluator _readAuthorizationsVisibilityEvaluator;
  private final ConcurrentLinkedHashMap<Integer, DocumentVisibility> _readOrdToDocumentVisibility;
  private final ConcurrentLinkedHashMap<Integer, DocumentVisibility> _discoverOrdToDocumentVisibility;
  private final Set<String> _discoverableFields;
  private final ThreadLocal<BytesRef> _ref = new ThreadLocal<BytesRef>() {
    @Override
    protected BytesRef initialValue() {
      return new BytesRef();
    }
  };

  private SortedDocValues _readFieldSortedDocValues;
  private SortedDocValues _discoverFieldSortedDocValues;

  public DocValueAccessLookup(Collection<String> readAuthorizations, Collection<String> discoverAuthorizations,
      Set<String> discoverableFields) {
    this(readAuthorizations, discoverAuthorizations, READ_FIELD, DISCOVER_FIELD, discoverableFields);
  }

  public DocValueAccessLookup(Collection<String> readAuthorizations, Collection<String> discoverAuthorizations,
      String readField, String discoverField, Set<String> discoverableFields) {
    _discoverableFields = new HashSet<String>(discoverableFields);
    // TODO need to pass in the discover code to change document if needed
    List<String> termAuth = new ArrayList<String>();
    termAuth.addAll(readAuthorizations);
    termAuth.addAll(discoverAuthorizations);
    _readUnionDiscoverAuthorizations = new DocumentAuthorizations(termAuth);
    _readUnionDiscoverVisibilityEvaluator = new DocumentVisibilityEvaluator(_readUnionDiscoverAuthorizations);
    _readAuthorizations = new DocumentAuthorizations(readAuthorizations);
    _readAuthorizationsVisibilityEvaluator = new DocumentVisibilityEvaluator(_readAuthorizations);
    _readField = readField;
    _discoverField = discoverField;
    _readOrdToDocumentVisibility = new ConcurrentLinkedHashMap.Builder<Integer, DocumentVisibility>()
        .maximumWeightedCapacity(1000).build();
    _discoverOrdToDocumentVisibility = new ConcurrentLinkedHashMap.Builder<Integer, DocumentVisibility>()
        .maximumWeightedCapacity(1000).build();
  }

  @Override
  public AccessLookup clone(AtomicReader in) throws IOException {
    try {
      DocValueAccessLookup clone = (DocValueAccessLookup) super.clone();
      clone._discoverFieldSortedDocValues = in.getSortedDocValues(_discoverField);
      clone._readFieldSortedDocValues = in.getSortedDocValues(_readField);
      return clone;
    } catch (CloneNotSupportedException e) {
      throw new IOException(e);
    }
  }

  @Override
  public boolean hasAccess(TYPE type, int docID) throws IOException {
    BytesRef ref = _ref.get();
    switch (type) {
    case DOCS_ENUM:
    case LIVEDOCS:
      return readOrDiscoverAccess(ref, docID);
    case DOCUMENT_FETCH_DISCOVER:
      return discoverAccess(ref, docID);
    case BINARY_DOC_VALUE:
    case DOCUMENT_FETCH_READ:
    case NORM_VALUE:
    case NUMERIC_DOC_VALUE:
    case SORTED_DOC_VALUE:
    case SORTED_SET_DOC_VALUE:
      return readAccess(ref, docID);
    default:
      throw new IOException("Unknown type [" + type + "]");
    }
  }

  private boolean readOrDiscoverAccess(BytesRef ref, int doc) throws IOException {
    if (readAccess(ref, doc)) {
      return true;
    }
    if (discoverAccess(ref, doc)) {
      return true;
    }
    return false;
  }

  private boolean discoverAccess(BytesRef ref, int doc) throws IOException {
    SortedDocValues discoverFieldSortedDocValues = _discoverFieldSortedDocValues;
    // Checking discovery access
    int ord = discoverFieldSortedDocValues.getOrd(doc);
    if (ord >= 0) {
      // If < 0 means there is no value.
      DocumentVisibility discoverDocumentVisibility = _discoverOrdToDocumentVisibility.get(ord);
      if (discoverDocumentVisibility == null) {
        discoverFieldSortedDocValues.get(doc, ref);
        discoverDocumentVisibility = new DocumentVisibility(ref.utf8ToString());
        _discoverOrdToDocumentVisibility.put(ord, discoverDocumentVisibility);
      }
      if (_readUnionDiscoverVisibilityEvaluator.evaluate(discoverDocumentVisibility)) {
        return true;
      }
    }
    return false;
  }

  private boolean readAccess(BytesRef ref, int doc) throws IOException {
    SortedDocValues readFieldSortedDocValues = _readFieldSortedDocValues;
    // Checking read access
    int ord = readFieldSortedDocValues.getOrd(doc);
    if (ord >= 0) {
      // If < 0 means there is no value.
      DocumentVisibility readDocumentVisibility = _readOrdToDocumentVisibility.get(ord);
      if (readDocumentVisibility == null) {
        readFieldSortedDocValues.get(doc, ref);
        readDocumentVisibility = new DocumentVisibility(ref.utf8ToString());
        _readOrdToDocumentVisibility.put(ord, readDocumentVisibility);
      }

      if (_readAuthorizationsVisibilityEvaluator.evaluate(readDocumentVisibility)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean canDiscoverField(String name) {
    return _discoverableFields.contains(name);
  }

  public static Iterable<IndexableField> addReadVisiblity(String read, Iterable<IndexableField> fields) {
    BytesRef value = new BytesRef(read);
    SortedDocValuesField docValueField = new SortedDocValuesField(READ_FIELD, value);
    StoredField storedField = new StoredField(READ_FIELD, value);
    return addField(fields, docValueField, storedField);
  }

  public static Iterable<IndexableField> addDiscoverVisiblity(String discover, Iterable<IndexableField> fields) {
    BytesRef value = new BytesRef(discover);
    SortedDocValuesField docValueField = new SortedDocValuesField(DISCOVER_FIELD, value);
    StoredField storedField = new StoredField(DISCOVER_FIELD, value);
    return addField(fields, docValueField, storedField);
  }

  private static Iterable<IndexableField> addField(Iterable<IndexableField> fields, SortedDocValuesField field,
      StoredField storedField) {
    if (fields instanceof Document) {
      Document document = (Document) fields;
      document.add(field);
      document.add(storedField);
      return document;
    }
    List<IndexableField> list = new ArrayList<IndexableField>();
    for (IndexableField indexableField : fields) {
      list.add(indexableField);
    }
    list.add(field);
    list.add(storedField);
    return list;
  }
}