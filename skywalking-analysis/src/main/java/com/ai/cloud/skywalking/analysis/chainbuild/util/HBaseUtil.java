package com.ai.cloud.skywalking.analysis.chainbuild.util;

import com.ai.cloud.skywalking.analysis.chainbuild.entity.CallChainTree;
import com.ai.cloud.skywalking.analysis.chainbuild.entity.CallChainTreeNode;
import com.ai.cloud.skywalking.analysis.chainbuild.entity.ChainNodeSpecificMinSummary;
import com.ai.cloud.skywalking.analysis.config.Config;
import com.ai.cloud.skywalking.analysis.config.HBaseTableMetaData;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.*;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class HBaseUtil {
    private static Logger logger = LoggerFactory.getLogger(HBaseUtil.class.getName());

    private static Configuration configuration = null;
    private static Connection connection;

    static {
        try {
            initHBaseClient();

            createTableIfNeed(HBaseTableMetaData.TABLE_CALL_CHAIN_TREE_DETAIL.TABLE_NAME,
                    HBaseTableMetaData.TABLE_CALL_CHAIN_TREE_DETAIL.COLUMN_FAMILY_NAME);

            createTableIfNeed(HBaseTableMetaData.TABLE_CALL_CHAIN_TREE_ID_AND_CID_MAPPING.TABLE_NAME,
                    HBaseTableMetaData.TABLE_CALL_CHAIN_TREE_ID_AND_CID_MAPPING.COLUMN_FAMILY_NAME);
        } catch (IOException e) {
            logger.error("Create tables failed", e);
        }
    }

    private static void createTableIfNeed(String tableName, String familyName) throws IOException {
        Admin admin = connection.getAdmin();
        if (!admin.isTableAvailable(TableName.valueOf(tableName))) {
            HTableDescriptor tableDesc = new HTableDescriptor(TableName.valueOf(tableName));
            tableDesc.addFamily(new HColumnDescriptor(familyName.getBytes()));
            admin.createTable(tableDesc);
            logger.info("Create table [{}] ok!", tableName);
        }
    }

    private static void initHBaseClient() throws IOException {
        if (configuration == null) {
            configuration = HBaseConfiguration.create();
            if (Config.HBase.ZK_QUORUM == null || "".equals(Config.HBase.ZK_QUORUM)) {
                logger.error("Miss HBase ZK quorum Configuration", new IllegalArgumentException("Miss HBase ZK quorum Configuration"));
                System.exit(-1);
            }
            configuration.set("hbase.zookeeper.quorum", Config.HBase.ZK_QUORUM);
            configuration.set("hbase.zookeeper.property.clientPort", Config.HBase.ZK_CLIENT_PORT);
            connection = ConnectionFactory.createConnection(configuration);
        }
    }

    public static ChainNodeSpecificMinSummary loadSpecificMinSummary(String key, String qualifier) throws IOException {
        ChainNodeSpecificMinSummary result = null;
        Table table = connection.getTable(TableName.valueOf(HBaseTableMetaData.TABLE_CHAIN_ONE_MINUTE_SUMMARY.TABLE_NAME));
        Get g = new Get(Bytes.toBytes(key));
        Result r = table.get(g);

        if (r.rawCells().length == 0) {
            return null;
        }

        Cell cell = r.getColumnLatestCell(HBaseTableMetaData.TABLE_CHAIN_ONE_MINUTE_SUMMARY.COLUMN_FAMILY_NAME.getBytes(),
                qualifier.getBytes());

        if (cell.getValueArray().length > 0) {
            result = new ChainNodeSpecificMinSummary(Bytes.toString(cell.getValueArray(),
                    cell.getValueOffset(), cell.getValueLength()));
        } else {
            result = new ChainNodeSpecificMinSummary();
        }

        return result;
    }

    public static CallChainTree loadCallChainTree(String callEntrance) throws IOException {
        CallChainTree result = null;
        Table table = connection.getTable(TableName.valueOf(HBaseTableMetaData.TABLE_CALL_CHAIN_TREE_DETAIL.TABLE_NAME));
        Get g = new Get(Bytes.toBytes(callEntrance));
        Result r = table.get(g);
        if (r.rawCells().length == 0) {
            return null;
        }
        result = new CallChainTree(callEntrance);
        for (Cell cell : r.rawCells()) {
            if (cell.getValueArray().length > 0)
                result.addMergedChainNode(new CallChainTreeNode(Bytes.toString(cell.getValueArray(),
                        cell.getValueOffset(), cell.getValueLength())));
        }
        return result;
    }

    public static void saveCallChainTree(CallChainTree callChainTree) throws IOException {
        // save
        Put callChainTreePut = new Put(callChainTree.getCallEntrance().getBytes());
        for (Map.Entry<String, CallChainTreeNode> entry : callChainTree.getNodes().entrySet()) {
            callChainTreePut.addColumn(HBaseTableMetaData.TABLE_CALL_CHAIN_TREE_DETAIL.COLUMN_FAMILY_NAME.getBytes(),
                    entry.getKey().getBytes(), entry.getValue().toString().getBytes());
        }
        Table table = connection.getTable(TableName.valueOf(HBaseTableMetaData.TABLE_CALL_CHAIN_TREE_DETAIL.TABLE_NAME));
        table.put(callChainTreePut);
        // save relationship
        Put treeIdCidMappingPut = new Put(callChainTree.getCallEntrance().getBytes());
        treeIdCidMappingPut.addColumn(HBaseTableMetaData.TABLE_CALL_CHAIN_TREE_ID_AND_CID_MAPPING.COLUMN_FAMILY_NAME.getBytes()
                , "HAS_BEEN_MERGED_CHAIN_ID".getBytes(), callChainTree.getHasBeenMergedChainIds().getBytes());
        Table relationshipTable = connection.getTable(TableName.valueOf(HBaseTableMetaData.TABLE_CALL_CHAIN_TREE_ID_AND_CID_MAPPING.TABLE_NAME));
        relationshipTable.put(treeIdCidMappingPut);
    }

    public static List<String> loadHasBeenMergeChainIds(String treeId) throws IOException {
        List<String> result = new ArrayList<String>();
        Table table = connection.getTable(TableName.valueOf(HBaseTableMetaData.TABLE_CALL_CHAIN_TREE_ID_AND_CID_MAPPING.TABLE_NAME));
        Get g = new Get(Bytes.toBytes(treeId));
        Result r = table.get(g);
        if (r.rawCells().length == 0) {
            return null;
        }
        for (Cell cell : r.rawCells()) {
            if (cell.getValueArray().length > 0) {
                List<String> hasBeenMergedCIds = new Gson().fromJson("",
                        new TypeToken<List<String>>() {
                        }.getType());
                result.addAll(hasBeenMergedCIds);
            }

        }
        return result;
    }

    public static void batchSaveMinSummaryResult(List<Put> puts) throws IOException, InterruptedException {
        Table table = connection.getTable(TableName.valueOf(HBaseTableMetaData.TABLE_CHAIN_ONE_MINUTE_SUMMARY.TABLE_NAME));
        Object[] resultArray = new Object[puts.size()];
        table.batch(puts, resultArray);
        int index = 0;
        for (Object result : resultArray) {
            if (result == null) {
                logger.error("Failed to insert the put the Value[" + puts.get(index).getId() + "]");
            }
            index++;
        }
    }
}
