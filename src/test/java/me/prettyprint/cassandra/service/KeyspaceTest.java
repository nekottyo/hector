package me.prettyprint.cassandra.service;

import static me.prettyprint.cassandra.utils.StringUtils.bytes;
import static me.prettyprint.cassandra.utils.StringUtils.string;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import me.prettyprint.cassandra.BaseEmbededServerSetupTest;
import me.prettyprint.cassandra.extractors.StringExtractor;
import me.prettyprint.cassandra.model.HectorException;
import me.prettyprint.cassandra.model.InvalidRequestException;
import me.prettyprint.cassandra.model.NotFoundException;
import me.prettyprint.cassandra.model.PoolExhaustedException;
import me.prettyprint.cassandra.model.TimedOutException;
import me.prettyprint.cassandra.service.CassandraClient.FailoverPolicy;

import org.apache.cassandra.thrift.Cassandra;
import org.apache.cassandra.thrift.Column;
import org.apache.cassandra.thrift.ColumnOrSuperColumn;
import org.apache.cassandra.thrift.ColumnParent;
import org.apache.cassandra.thrift.ColumnPath;
import org.apache.cassandra.thrift.ConsistencyLevel;
import org.apache.cassandra.thrift.Deletion;
import org.apache.cassandra.thrift.KeyRange;
import org.apache.cassandra.thrift.Mutation;
import org.apache.cassandra.thrift.SlicePredicate;
import org.apache.cassandra.thrift.SliceRange;
import org.apache.cassandra.thrift.SuperColumn;
import org.apache.cassandra.thrift.UnavailableException;
import org.apache.commons.lang.StringUtils;
import org.apache.thrift.TException;
import org.apache.thrift.transport.TTransportException;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Matchers;

/**
 * For the tests we assume the following structure:
 *
 * &lt;Keyspaces&gt; &lt;Keyspace Name="Keyspace1"&gt; &lt;ColumnFamily
 * CompareWith="BytesType" Name="Standard1" FlushPeriodInMinutes="60"/&gt;
 * &lt;ColumnFamily CompareWith="UTF8Type" Name="Standard2"/&gt;
 * &lt;ColumnFamily CompareWith="TimeUUIDType" Name="StandardByUUID1"/&gt;
 * &lt;ColumnFamily ColumnType="Super" CompareWith="UTF8Type"
 * CompareSubcolumnsWith="UTF8Type" Name="Super1"/&gt;
 *
 * @author Ran Tavory (rantav@gmail.com)
 *
 */
public class KeyspaceTest extends BaseEmbededServerSetupTest {

  private CassandraClient client;
  private Keyspace keyspace;
  private static final StringExtractor se = new StringExtractor();

  @Before
  public void setupCase() throws IllegalStateException, PoolExhaustedException, Exception {
    super.setupClient();
    client = new CassandraClientFactory(pools,
        new CassandraHost("127.0.0.1", 9170), JmxMonitor.getInstance().getCassandraMonitor()).create();

    keyspace = client.getKeyspace("Keyspace1", ConsistencyLevel.ONE,
        CassandraClient.DEFAULT_FAILOVER_POLICY);
  }

  @Test
  public void testInsertAndGetAndRemove() throws IllegalArgumentException, NoSuchElementException,
  IllegalStateException, NotFoundException, Exception {

    // insert value
    ColumnPath cp = new ColumnPath("Standard1");
    cp.setColumn(bytes("testInsertAndGetAndRemove"));
    for (int i = 0; i < 100; i++) {
      keyspace.insert("testInsertAndGetAndRemove_" + i, cp,
          bytes("testInsertAndGetAndRemove_value_" + i));
    }

    // get value
    for (int i = 0; i < 100; i++) {
      Column col = keyspace.getColumn("testInsertAndGetAndRemove_" + i, cp);
      assertNotNull(col);
      String value = string(col.getValue());
      assertEquals("testInsertAndGetAndRemove_value_" + i, value);
    }

    // remove value
    for (int i = 0; i < 100; i++) {
      keyspace.remove("testInsertAndGetAndRemove_" + i, cp);
    }

    // get already removed value
    for (int i = 0; i < 100; i++) {
      try {
        keyspace.getColumn("testInsertAndGetAndRemove_" + i, cp);
        fail("the value should already being deleted");
      } catch (NotFoundException e) {
        // good
      }
    }
  }

  /**
   * Test insertion of a supercolumn using insert
   */
  @Test
  public void testInsertSuper() throws IllegalArgumentException, NoSuchElementException,
  IllegalStateException, NotFoundException, Exception {

    // insert value
    ColumnPath cp = new ColumnPath("Super1");
    cp.setColumn(bytes("testInsertSuper_column"));
    cp.setSuper_column(bytes("testInsertSuper_super"));
    keyspace.insert("testInsertSuper_key", cp, bytes("testInsertSuper_value"));
    cp.setColumn(bytes("testInsertSuper_column2"));
    keyspace.insert("testInsertSuper_key", cp, bytes("testInsertSuper_value2"));

    // get value and assert
    ColumnPath cp2 = new ColumnPath("Super1");
    cp2.setSuper_column(bytes("testInsertSuper_super"));
    SuperColumn sc = keyspace.getSuperColumn("testInsertSuper_key", cp2);
    assertNotNull(sc);
    assertEquals("testInsertSuper_super", string(sc.getName()));
    assertEquals(2, sc.getColumns().size());
    assertEquals("testInsertSuper_value", string(sc.getColumns().get(0).getValue()));

    // remove value
    keyspace.remove("testInsertSuper_super", cp);
  }

  @Test
  public void testValideColumnPath() throws HectorException {
    // Try to insert invalid columns
    // insert value
    ColumnPath cp = new ColumnPath("Standard1");
    cp.setColumn(bytes("testValideColumnPath"));
    try {
      keyspace.insert("testValideColumnPath", cp, bytes("testValideColumnPath_value"));
      keyspace.remove("testValideColumnPath", cp);
    } catch (InvalidRequestException e) {
      fail("Should not have thrown an error for Standard1");
    }

    cp = new ColumnPath("CFdoesNotExist");
    cp.setColumn(bytes("testInsertAndGetAndRemove"));
    try {
      keyspace.insert("testValideColumnPath", cp, bytes("testValideColumnPath_value"));
      fail("Should have failed with CFdoesNotExist");
    } catch (InvalidRequestException e) {
      assertTrue(StringUtils.contains(e.getWhy(),"column family does not exist"));
    }

    cp = new ColumnPath("Standard1");
    cp.setSuper_column(bytes("testInsertAndGetAndRemove"));
    try {
      keyspace.insert("testValideColumnPath", cp, bytes("testValideColumnPath_value"));
      fail("Should have failed with supercolumn");
    } catch (InvalidRequestException e) {
      assertTrue(StringUtils.contains(e.getWhy(),"Make sure you have the right type"));
    }

    cp = new ColumnPath("Super1");
    cp.setColumn(bytes("testInsertAndGetAndRemove"));
    try {
      keyspace.insert("testValideColumnPath", cp, bytes("testValideColumnPath_value"));
      fail("Should have failed with supercolumn");
    } catch (InvalidRequestException e) {
      assertTrue(StringUtils.contains(e.getWhy(),"Make sure you have"));
    }
  }

  @Test
  public void testBatchInsertColumn() throws HectorException {
    // FIXME replace batchInserts
    /*
    for (int i = 0; i < 10; i++) {
      HashMap<String, List<Column>> cfmap = new HashMap<String, List<Column>>(10);
      ArrayList<Column> list = new ArrayList<Column>(10);
      for (int j = 0; j < 10; j++) {
        Column col = new Column(bytes("testBatchInsertColumn_" + j),
            bytes("testBatchInsertColumn_value_" + j), keyspace.createClock());
        list.add(col);
      }
      cfmap.put("Standard1", list);

      keyspace.batchInsert("testBatchInsertColumn_" + i, cfmap, null);
    }

    // get value
    for (int i = 0; i < 10; i++) {
      for (int j = 0; j < 10; j++) {
        ColumnPath cp = new ColumnPath("Standard1");
        cp.setColumn(bytes("testBatchInsertColumn_" + j));

        Column col = keyspace.getColumn("testBatchInsertColumn_" + i, cp);
        assertNotNull(col);
        String value = string(col.getValue());
        assertEquals("testBatchInsertColumn_value_" + j, value);

      }
    }

    // remove value
    for (int i = 0; i < 10; i++) {
      for (int j = 0; j < 10; j++) {
        ColumnPath cp = new ColumnPath("Standard1");
        cp.setColumn(bytes("testBatchInsertColumn_" + j));
        keyspace.remove("testBatchInsertColumn_" + i, cp);
      }
    }
    */
  }

  @Test
  public void testBatchMutate() throws HectorException {
    Map<String, Map<String, List<Mutation>>> outerMutationMap = new HashMap<String, Map<String,List<Mutation>>>();
    for (int i = 0; i < 10; i++) {

      Map<String, List<Mutation>> mutationMap = new HashMap<String, List<Mutation>>();

      ArrayList<Mutation> mutations = new ArrayList<Mutation>(10);
      for (int j = 0; j < 10; j++) {
        Column col = new Column(bytes("testBatchMutateColumn_" + j),
            bytes("testBatchMutateColumn_value_" + j), keyspace.createClock());
        //list.add(col);
        ColumnOrSuperColumn cosc = new ColumnOrSuperColumn();
        cosc.setColumn(col);
        Mutation mutation = new Mutation();
        mutation.setColumn_or_supercolumn(cosc);
        mutations.add(mutation);
      }
      mutationMap.put("Standard1", mutations);
      outerMutationMap.put("testBatchMutateColumn_" + i, mutationMap);
    }
    keyspace.batchMutate(se.toBytesMap(outerMutationMap));
    // re-use later
    outerMutationMap.clear();

    // get value
    for (int i = 0; i < 10; i++) {
      for (int j = 0; j < 10; j++) {
        ColumnPath cp = new ColumnPath("Standard1");
        cp.setColumn(bytes("testBatchMutateColumn_" + j));

        Column col = keyspace.getColumn("testBatchMutateColumn_" + i, cp);
        assertNotNull(col);
        String value = string(col.getValue());
        assertEquals("testBatchMutateColumn_value_" + j, value);

      }
    }

    // batch_mutate delete by key
    for (int i = 0; i < 10; i++) {
      ArrayList<Mutation> mutations = new ArrayList<Mutation>(10);
      Map<String, List<Mutation>> mutationMap = new HashMap<String, List<Mutation>>();
      SlicePredicate slicePredicate = new SlicePredicate();
      for (int j = 0; j < 10; j++) {
        slicePredicate.addToColumn_names(bytes("testBatchMutateColumn_" + j));
      }
      Mutation mutation = new Mutation();
      Deletion deletion = new Deletion(keyspace.createClock());
      deletion.setPredicate(slicePredicate);
      mutation.setDeletion(deletion);
      mutations.add(mutation);

      mutationMap.put("Standard1", mutations);
      outerMutationMap.put("testBatchMutateColumn_"+i, mutationMap);
    }
    keyspace.batchMutate(se.toBytesMap(outerMutationMap));
    // make sure the values are gone
    for (int i = 0; i < 10; i++) {
      for (int j = 0; j < 10; j++) {
        ColumnPath cp = new ColumnPath("Standard1");
        cp.setColumn(bytes("testBatchMutateColumn_" + j));
        try {
          keyspace.getColumn("testBatchMutateColumn_" + i, cp);
          fail();
        } catch (NotFoundException e) {
        }

      }
    }
  }

  @Test
  public void testBatchMutateBatchMutation() throws HectorException {
    BatchMutation<String> batchMutation = new BatchMutation<String>(se);
    List<String> columnFamilies = Arrays.asList("Standard1");
    for (int i = 0; i < 10; i++) {

      for (int j = 0; j < 10; j++) {
        Column col = new Column(bytes("testBatchMutateColumn_" + j),
            bytes("testBatchMutateColumn_value_" + j), keyspace.createClock());
        batchMutation.addInsertion("testBatchMutateColumn_" + i, columnFamilies, col);
      }
    }
    keyspace.batchMutate(batchMutation);

    // get value
    for (int i = 0; i < 10; i++) {
      for (int j = 0; j < 10; j++) {
        ColumnPath cp = new ColumnPath("Standard1");
        cp.setColumn(bytes("testBatchMutateColumn_" + j));

        Column col = keyspace.getColumn("testBatchMutateColumn_" + i, cp);
        assertNotNull(col);
        String value = string(col.getValue());
        assertEquals("testBatchMutateColumn_value_" + j, value);

      }
    }
    batchMutation = new BatchMutation<String>(se);
    // batch_mutate delete by key
    for (int i = 0; i < 10; i++) {
      SlicePredicate slicePredicate = new SlicePredicate();
      for (int j = 0; j < 10; j++) {
        slicePredicate.addToColumn_names(bytes("testBatchMutateColumn_" + j));
      }
      Deletion deletion = new Deletion(keyspace.createClock());
      deletion.setPredicate(slicePredicate);
      batchMutation.addDeletion("testBatchMutateColumn_" + i, columnFamilies, deletion);
    }
    keyspace.batchMutate(batchMutation);
    // make sure the values are gone
    for (int i = 0; i < 10; i++) {
      for (int j = 0; j < 10; j++) {
        ColumnPath cp = new ColumnPath("Standard1");
        cp.setColumn(bytes("testBatchMutateColumn_" + j));
        try {
          keyspace.getColumn("testBatchMutateColumn_" + i, cp);
          fail();
        } catch (NotFoundException e) {
          // good, we want this to throw.
        }

      }
    }
  }

  @Test
  public void testBatchUpdateInsertAndDelOnSame() throws HectorException {

    ColumnPath sta1 = new ColumnPath("Standard1");
    sta1.setColumn(bytes("deleteThroughInserBatch_col"));

    keyspace.insert("deleteThroughInserBatch_key", sta1, bytes("deleteThroughInserBatch_val"));

    Column found = keyspace.getColumn("deleteThroughInserBatch_key", sta1);
    assertNotNull(found);

    BatchMutation<String> batchMutation = new BatchMutation<String>(se);
    List<String> columnFamilies = Arrays.asList("Standard1");
    for (int i = 0; i < 10; i++) {

      for (int j = 0; j < 10; j++) {
        Column col = new Column(bytes("testBatchMutateColumn_" + j),
            bytes("testBatchMutateColumn_value_" + j), keyspace.createClock());
        batchMutation.addInsertion("testBatchMutateColumn_" + i, columnFamilies, col);
      }
    }
    SlicePredicate slicePredicate = new SlicePredicate();
    slicePredicate.addToColumn_names(bytes("deleteThroughInserBatch_col"));

    Deletion deletion = new Deletion(keyspace.createClock());
    deletion.setPredicate(slicePredicate);

    batchMutation.addDeletion("deleteThroughInserBatch_key", columnFamilies, deletion);
    keyspace.batchMutate(batchMutation);
    try {
      keyspace.getColumn("deleteThroughInserBatch_key", sta1);
      fail("Should not have found a value here");
    } catch (Exception e) {
    }
    // get value
    for (int i = 0; i < 10; i++) {
      for (int j = 0; j < 10; j++) {
        ColumnPath cp = new ColumnPath("Standard1");
        cp.setColumn(bytes("testBatchMutateColumn_" + j));

        Column col = keyspace.getColumn("testBatchMutateColumn_" + i, cp);
        assertNotNull(col);
        String value = string(col.getValue());
        assertEquals("testBatchMutateColumn_value_" + j, value);

      }
    }
  }

  @Test
  public void testGetClient() {
    assertEquals(client, keyspace.getClient());
  }

  @Test
  public void testGetSuperColumn() throws HectorException {
    // FIXME replace batchInserts
    /*
    HashMap<String, List<SuperColumn>> cfmap = new HashMap<String, List<SuperColumn>>(10);
    ArrayList<Column> list = new ArrayList<Column>(100);
    for (int j = 0; j < 10; j++) {
      Column col = new Column(bytes("testGetSuperColumn_" + j), bytes("testGetSuperColumn_value_"
          + j), keyspace.createClock());
      list.add(col);
    }
    ArrayList<SuperColumn> superlist = new ArrayList<SuperColumn>(1);
    SuperColumn sc = new SuperColumn(bytes("SuperColumn_1"), list);
    superlist.add(sc);
    cfmap.put("Super1", superlist);
    keyspace.batchInsert("testGetSuperColumn_1", null, cfmap);

    ColumnPath cp = new ColumnPath("Super1");
    cp.setSuper_column(bytes("SuperColumn_1"));
    try {
      SuperColumn superc = keyspace.getSuperColumn("testGetSuperColumn_1", cp);
      assertNotNull(superc);
      assertNotNull(superc.getColumns());
      assertEquals(10, superc.getColumns().size());
    } finally {
      keyspace.remove("testGetSuperColumn_1", cp);
    }
    */
  }

  @Test
  public void testGetSlice() throws HectorException {
    // insert value
    ArrayList<String> columnnames = new ArrayList<String>(100);
    for (int i = 0; i < 100; i++) {
      ColumnPath cp = new ColumnPath("Standard2");
      cp.setColumn(bytes("testGetSlice_" + i));
      keyspace.insert("testGetSlice", cp, bytes("testGetSlice_Value_" + i));
      columnnames.add("testGetSlice_" + i);
    }

    // get value
    ColumnParent clp = new ColumnParent("Standard2");
    SliceRange sr = new SliceRange(new byte[0], new byte[0], false, 150);
    SlicePredicate sp = new SlicePredicate();
    sp.setSlice_range(sr);
    List<Column> cols = keyspace.getSlice("testGetSlice", clp, sp);

    assertNotNull(cols);
    assertEquals(100, cols.size());

    Collections.sort(columnnames);
    ArrayList<String> gotlist = new ArrayList<String>(100);
    for (int i = 0; i < 100; i++) {
      gotlist.add(string(cols.get(i).getName()));
    }
    assertEquals(columnnames, gotlist);

    ColumnPath cp = new ColumnPath("Standard2");
    keyspace.remove("testGetSlice_", cp);
    keyspace.remove("testGetSlice", cp);
  }

  @Test
  public void testGetSuperSlice() throws HectorException {
    // insert value
    for (int i = 0; i < 100; i++) {
      ColumnPath cp = new ColumnPath("Super1");

      cp.setSuper_column(bytes("SuperColumn_1"));
      cp.setColumn(bytes("testGetSuperSlice_"+ i));

      ColumnPath cp2 = new ColumnPath("Super1");

      cp2.setSuper_column(bytes("SuperColumn_2"));
      cp2.setColumn(bytes("testGetSuperSlice_" + i));

      keyspace.insert("testGetSuperSlice", cp, bytes("testGetSuperSlice_Value_" + i));
      keyspace.insert("testGetSuperSlice", cp2, bytes("testGetSuperSlice_Value_" + i));
    }

    // get value
    ColumnParent clp = new ColumnParent("Super1");
    SliceRange sr = new SliceRange(new byte[0], new byte[0], false, 150);
    SlicePredicate sp = new SlicePredicate();
    sp.setSlice_range(sr);
    List<SuperColumn> cols = keyspace.getSuperSlice("testGetSuperSlice", clp, sp);

    assertNotNull(cols);
    assertEquals(2, cols.size());

    ColumnPath cp = new ColumnPath("Super1");
    keyspace.remove("testGetSuperSlice", cp);
  }

  @Test
  public void testMultigetColumn() throws HectorException {
    // insert value
    ColumnPath cp = new ColumnPath("Standard1");
    cp.setColumn(bytes("testMultigetColumn"));
    ArrayList<String> keys = new ArrayList<String>(100);
    for (int i = 0; i < 100; i++) {
      keyspace.insert("testMultigetColumn_" + i, cp, bytes("testMultigetColumn_value_" + i));
      keys.add("testMultigetColumn_" + i);
    }

    // get value
    /*
    Map<String, Column> ms = keyspace.multigetColumn(keys, cp);
    for (int i = 0; i < 100; i++) {
      Column cl = ms.get(keys.get(i));
      assertNotNull(cl);
      assertEquals("testMultigetColumn_value_" + i, string(cl.getValue()));
    }
    */

    // remove value
    for (int i = 0; i < 100; i++) {
      keyspace.remove("testMultigetColumn_" + i, cp);
    }
  }

  @Test
  public void testMultigetSuperColumn() throws HectorException {
    // FIXME replace batchInserts
    /*
    HashMap<String, List<SuperColumn>> cfmap = new HashMap<String, List<SuperColumn>>(10);
    ArrayList<Column> list = new ArrayList<Column>(100);
    for (int j = 0; j < 10; j++) {
      Column col = new Column(bytes("testMultigetSuperColumn_" + j),
          bytes("testMultigetSuperColumn_value_" + j), keyspace.createClock());
      list.add(col);
    }
    ArrayList<SuperColumn> superlist = new ArrayList<SuperColumn>(1);
    SuperColumn sc = new SuperColumn(bytes("SuperColumn_1"), list);
    superlist.add(sc);
    cfmap.put("Super1", superlist);
    keyspace.batchInsert("testMultigetSuperColumn_1", null, cfmap);

    ColumnPath cp = new ColumnPath("Super1");
    cp.setSuper_column(bytes("SuperColumn_1"));
    try {
      List<String> keys = new ArrayList<String>();
      keys.add("testMultigetSuperColumn_1");
      Map<String, SuperColumn> superc = keyspace.multigetSuperColumn(keys, cp, se);
      assertNotNull(superc);
      assertEquals(1, superc.size());
      assertEquals(10, superc.get("testMultigetSuperColumn_1").columns.size());
    } finally {
      keyspace.remove("testMultigetSuperColumn_1", cp);
    */
  }

  @Test
  public void testMultigetSlice() throws HectorException {
    // insert value
    ColumnPath cp = new ColumnPath("Standard1");
    cp.setColumn(bytes("testMultigetSlice"));
    ArrayList<String> keys = new ArrayList<String>(100);
    for (int i = 0; i < 100; i++) {
      keyspace.insert("testMultigetSlice_" + i, cp, bytes("testMultigetSlice_value_" + i));
      keys.add("testMultigetSlice_" + i);
    }
    // get value
    ColumnParent clp = new ColumnParent("Standard1");
    SliceRange sr = new SliceRange(new byte[0], new byte[0], false, 150);
    SlicePredicate sp = new SlicePredicate();
    sp.setSlice_range(sr);
    Map<String, List<Column>> ms = se.fromBytesMap(keyspace.multigetSlice(se.toBytesList(keys), clp, sp));
    for (int i = 0; i < 100; i++) {
      List<Column> cl = ms.get(keys.get(i));
      assertNotNull(cl);
      assertEquals(1, cl.size());
      assertTrue(string(cl.get(0).getValue()).startsWith("testMultigetSlice_"));
    }

    // remove value
    for (int i = 0; i < 100; i++) {
      keyspace.remove("testMultigetSlice_" + i, cp);
    }
  }

  @Test
  public void testMultigetSlice_1() throws HectorException {
    // FIXME replace batchInserts
    /*
    HashMap<String, List<SuperColumn>> cfmap = new HashMap<String, List<SuperColumn>>(10);
    ArrayList<Column> list = new ArrayList<Column>(100);
    for (int j = 0; j < 10; j++) {
      Column col = new Column(bytes("testMultigetSuperSlice_" + j),
          bytes("testMultigetSuperSlice_value_" + j), keyspace.createClock());
      list.add(col);
    }
    ArrayList<SuperColumn> superlist = new ArrayList<SuperColumn>(1);
    SuperColumn sc = new SuperColumn(bytes("SuperColumn_1"), list);
    SuperColumn sc2 = new SuperColumn(bytes("SuperColumn_2"), list);
    superlist.add(sc);
    superlist.add(sc2);
    cfmap.put("Super1", superlist);
    keyspace.batchInsert("testMultigetSuperSlice_1", null, cfmap);
    keyspace.batchInsert("testMultigetSuperSlice_2", null, cfmap);
    keyspace.batchInsert("testMultigetSuperSlice_3", null, cfmap);

    try {
      List<String> keys = new ArrayList<String>();
      keys.add("testMultigetSuperSlice_1");
      keys.add("testMultigetSuperSlice_2");
      keys.add("testMultigetSuperSlice_3");

      ColumnParent clp = new ColumnParent("Super1");
      clp.setSuper_column(bytes("SuperColumn_1"));
      SliceRange sr = new SliceRange(new byte[0], new byte[0], false, 150);
      SlicePredicate sp = new SlicePredicate();
      sp.setSlice_range(sr);
      Map<String, List<Column>> superc = keyspace.multigetSlice(keys, clp, sp, se);

      assertNotNull(superc);
      assertEquals(3, superc.size());
      List<Column> scls = superc.get("testMultigetSuperSlice_1");
      assertNotNull(scls);
      assertEquals(10, scls.size());

    } finally {
      // insert value
      ColumnPath cp = new ColumnPath("Super1");
      keyspace.remove("testMultigetSuperSlice_1", cp);
      keyspace.remove("testMultigetSuperSlice_2", cp);
      keyspace.remove("testMultigetSuperSlice_3", cp);
    }
    */
  }

  @Test
  public void testMultigetSuperSlice() throws HectorException {
    // FIXME replace batchInserts
    /*
    HashMap<String, List<SuperColumn>> cfmap = new HashMap<String, List<SuperColumn>>(10);
    ArrayList<Column> list = new ArrayList<Column>(100);
    for (int j = 0; j < 10; j++) {
      Column col = new Column(bytes("testMultigetSuperSlice_" + j),
          bytes("testMultigetSuperSlice_value_" + j), keyspace.createClock());
      list.add(col);
    }
    ArrayList<SuperColumn> superlist = new ArrayList<SuperColumn>(1);
    SuperColumn sc = new SuperColumn(bytes("SuperColumn_1"), list);
    SuperColumn sc2 = new SuperColumn(bytes("SuperColumn_2"), list);
    superlist.add(sc);
    superlist.add(sc2);
    cfmap.put("Super1", superlist);
    keyspace.batchInsert("testMultigetSuperSlice_1", null, cfmap);
    keyspace.batchInsert("testMultigetSuperSlice_2", null, cfmap);
    keyspace.batchInsert("testMultigetSuperSlice_3", null, cfmap);

    try {
      List<String> keys = new ArrayList<String>();
      keys.add("testMultigetSuperSlice_1");
      keys.add("testMultigetSuperSlice_2");
      keys.add("testMultigetSuperSlice_3");

      ColumnParent clp = new ColumnParent("Super1");
      SliceRange sr = new SliceRange(new byte[0], new byte[0], false, 150);
      SlicePredicate sp = new SlicePredicate();
      sp.setSlice_range(sr);
      Map<String, List<SuperColumn>> superc = keyspace.multigetSuperSlice(keys, clp, sp, se); // throw

      assertNotNull(superc);
      assertEquals(3, superc.size());
      List<SuperColumn> scls = superc.get("testMultigetSuperSlice_1");
      assertNotNull(scls);
      assertEquals(2, scls.size());
      assertNotNull(scls.get(0).getColumns());
      assertEquals(10, scls.get(0).getColumns().size());
      assertNotNull(scls.get(0).getColumns().get(0).value);
    } finally {
      // cleanup
      ColumnPath cp = new ColumnPath("Super1");
      keyspace.remove("testMultigetSuperSlice_1", cp);
      keyspace.remove("testMultigetSuperSlice_2", cp);
      keyspace.remove("testMultigetSuperSlice_3", cp);
    }
    */
  }

  @Test
  public void testDescribeKeyspace() throws HectorException {
    Map<String, Map<String, String>> description = keyspace.describeKeyspace();
    assertNotNull(description);
    assertEquals(4, description.size());
  }


  @Test
  public void testGetCount() throws HectorException {
    // insert values
    for (int i = 0; i < 100; i++) {
      ColumnPath cp = new ColumnPath("Standard1");
      cp.setColumn(bytes("testInsertAndGetAndRemove_" + i));
      keyspace.insert("testGetCount", cp, bytes("testInsertAndGetAndRemove_value_" + i));
    }

    // get value
    ColumnParent clp = new ColumnParent("Standard1");
    //int count = keyspace.getCount("testGetCount", clp, se);
    //assertEquals(100, count);

    ColumnPath cp = new ColumnPath("Standard1");
    keyspace.remove("testGetCount", cp);
  }

  @Test
  public void testGetRangeSlice() throws HectorException {
    for (int i = 0; i < 10; i++) {
      ColumnPath cp = new ColumnPath("Standard2");
      cp.setColumn(bytes("testGetRangeSlice_" + i));

      keyspace.insert("testGetRangeSlice0", cp, bytes("testGetRangeSlice_Value_" + i));
      keyspace.insert("testGetRangeSlice1", cp, bytes("testGetRangeSlice_Value_" + i));
      keyspace.insert("testGetRangeSlice2", cp, bytes("testGetRangeSlice_Value_" + i));
    }

    // get value
    ColumnParent clp = new ColumnParent("Standard2");
    SliceRange sr = new SliceRange(new byte[0], new byte[0], false, 150);
    SlicePredicate sp = new SlicePredicate();
    sp.setSlice_range(sr);
    /*
    @SuppressWarnings("deprecation")
    Map<String, List<Column>> keySlices = keyspace.getRangeSlice(clp, sp, "testGetRangeSlice0", "testGetRangeSlice3", 5);

    assertNotNull(keySlices);
    assertEquals(3, keySlices.size());
    assertNotNull("testGetRangeSlice1 is null", keySlices.get("testGetRangeSlice1"));
    assertEquals("testGetRangeSlice_Value_0", string(keySlices.get("testGetRangeSlice1").get(0).getValue()));
    assertEquals(10, keySlices.get("testGetRangeSlice1").size());
    */

    ColumnPath cp = new ColumnPath("Standard2");
    keyspace.remove("testGetRanageSlice0", cp);
    keyspace.remove("testGetRanageSlice1", cp);
    keyspace.remove("testGetRanageSlice2", cp);
  }

  @Test
  public void testGetRangeSlices() throws HectorException {
    for (int i = 0; i < 10; i++) {
      ColumnPath cp = new ColumnPath("Standard2");
      cp.setColumn(bytes("testGetRangeSlices_" + i));

      keyspace.insert("testGetRangeSlices0", cp, bytes("testGetRangeSlices_Value_" + i));
      keyspace.insert("testGetRangeSlices1", cp, bytes("testGetRangeSlices_Value_" + i));
      keyspace.insert("testGetRangeSlices2", cp, bytes("testGetRangeSlices_Value_" + i));
    }

    // get value
    ColumnParent clp = new ColumnParent("Standard2");
    SliceRange sr = new SliceRange(new byte[0], new byte[0], false, 150);
    SlicePredicate sp = new SlicePredicate();
    sp.setSlice_range(sr);

    KeyRange range = new KeyRange();
    range.setStart_key( "testGetRangeSlices0".getBytes());
    range.setEnd_key( "testGetRangeSlices2".getBytes());

    Map<String, List<Column>> keySlices = se.fromBytesMap(keyspace.getRangeSlices(clp, sp, range));

    assertNotNull(keySlices);
    assertEquals(3, keySlices.size());
    assertNotNull("testGetRangeSlices1 is null", keySlices.get("testGetRangeSlices1"));
    assertEquals("testGetRangeSlices_Value_0", string(keySlices.get("testGetRangeSlices1").get(0).getValue()));
    assertEquals(10, keySlices.get("testGetRangeSlices1").size());

    ColumnPath cp = new ColumnPath("Standard2");
    keyspace.remove("testGetRanageSlices0", cp);
    keyspace.remove("testGetRanageSlices1", cp);
    keyspace.remove("testGetRanageSlices2", cp);
  }

  @Test
  public void testGetSuperRangeSlice() throws HectorException {
    for (int i = 0; i < 10; i++) {
      ColumnPath cp = new ColumnPath("Super1");
      cp.setSuper_column(bytes("SuperColumn_1"));
      cp.setColumn(bytes("testGetSuperRangeSlice_" + i));
      keyspace.insert("testGetSuperRangeSlice0", cp, bytes("testGetSuperRangeSlice_Value_" + i));
      keyspace.insert("testGetSuperRangeSlice1", cp, bytes("testGetSuperRangeSlice_Value_" + i));
    }

    // get value
    ColumnParent clp = new ColumnParent("Super1");
    SliceRange sr = new SliceRange(new byte[0], new byte[0], false, 150);
    SlicePredicate sp = new SlicePredicate();
    sp.setSlice_range(sr);
    
    /*
    @SuppressWarnings("deprecation")
    Map<String, List<SuperColumn>> keySlices = keyspace.getSuperRangeSlice(clp, sp,
        "testGetSuperRangeSlice0", "testGetSuperRangeSlice3", 5);

    assertNotNull(keySlices);
    assertEquals(2, keySlices.size());
    assertNotNull("testGetSuperRangSlice0 is null", keySlices.get("testGetSuperRangeSlice0"));
    assertEquals("testGetSuperRangeSlice_Value_0",
        string(keySlices.get("testGetSuperRangeSlice0").get(0).getColumns().get(0).getValue()));
    assertEquals(1, keySlices.get("testGetSuperRangeSlice1").size());
    assertEquals(10, keySlices.get("testGetSuperRangeSlice1").get(0).getColumns().size());
    */
    
    ColumnPath cp = new ColumnPath("Super1");
    keyspace.remove("testGetSuperRangeSlice0", cp);
    keyspace.remove("testGetSuperRangeSlice1", cp);
  }

  @Test
  public void testGetSuperRangeSlices() throws HectorException {
    for (int i = 0; i < 10; i++) {
      ColumnPath cp = new ColumnPath("Super1");
      cp.setSuper_column(bytes("SuperColumn_1"));
      cp.setColumn(bytes("testGetSuperRangeSlices_" + i));
      keyspace.insert("testGetSuperRangeSlices0", cp, bytes("testGetSuperRangeSlices_Value_" + i));
      keyspace.insert("testGetSuperRangeSlices1", cp, bytes("testGetSuperRangeSlices_Value_" + i));
    }

    // get value
    ColumnParent clp = new ColumnParent("Super1");
    SliceRange sr = new SliceRange(new byte[0], new byte[0], false, 150);
    SlicePredicate sp = new SlicePredicate();
    sp.setSlice_range(sr);

    KeyRange range = new KeyRange();
    range.setStart_key( "testGetSuperRangeSlices0".getBytes());
    range.setEnd_key( "testGetSuperRangeSlices1".getBytes());


    Map<String, List<SuperColumn>> keySlices = se.fromBytesMap(keyspace.getSuperRangeSlices(clp, sp, range));

    assertNotNull(keySlices);
    assertEquals(2, keySlices.size());
    assertNotNull("testGetSuperRangSlices0 is null", keySlices.get("testGetSuperRangeSlices0"));
    assertEquals("testGetSuperRangeSlices_Value_0",
        string(keySlices.get("testGetSuperRangeSlices0").get(0).getColumns().get(0).getValue()));
    assertEquals(1, keySlices.get("testGetSuperRangeSlices1").size());
    assertEquals(10, keySlices.get("testGetSuperRangeSlices1").get(0).getColumns().size());

    ColumnPath cp = new ColumnPath("Super1");
    keyspace.remove("testGetSuperRangeSlices0", cp);
    keyspace.remove("testGetSuperRangeSlices1", cp);
  }

  @Test
  public void testGetConsistencyLevel() {
    assertEquals(ConsistencyLevel.ONE, keyspace.getConsistencyLevel());
  }

  @Test
  public void testGetKeyspaceName() {
    assertEquals("Keyspace1", keyspace.getName());
  }






}
