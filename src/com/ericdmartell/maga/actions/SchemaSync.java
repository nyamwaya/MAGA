package com.ericdmartell.maga.actions;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.reflections.Reflections;

import com.ericdmartell.maga.associations.MAGAAssociation;
import com.ericdmartell.maga.cache.MAGACache;
import com.ericdmartell.maga.objects.MAGAObject;
import com.ericdmartell.maga.utils.JDBCUtil;
import com.ericdmartell.maga.utils.MAGAException;
import com.ericdmartell.maga.utils.ReflectionUtils;

import gnu.trove.map.hash.THashMap;

public class SchemaSync {
	private DataSource dataSource;
	private MAGACache cache;

	public SchemaSync(DataSource dataSource, MAGACache cache) {
		this.dataSource = dataSource;
		this.cache = cache;
	}

	public void go() {
		String schema = JDBCUtil.executeQueryAndReturnSingleString(dataSource, "select database()");
		Connection connection = JDBCUtil.getConnection(dataSource);
		try {
			cache.flush();
			Reflections reflections = new Reflections("");
			List<Class<MAGAObject>> classes = new ArrayList(reflections.getSubTypesOf(MAGAObject.class));

			for (Class<MAGAObject> clazz : classes) {
				String tableName = clazz.getSimpleName();

				boolean tableExists = JDBCUtil.executeQueryAndReturnSingleLong(dataSource,
						"SELECT count(*) FROM information_schema.TABLES WHERE  (TABLE_NAME = ?) and (TABLE_SCHEMA = ?)", tableName, schema) == 1;
				if (!tableExists) {
					JDBCUtil.executeUpdate(
							"create table " + tableName + "(id int(11) not null AUTO_INCREMENT, primary key(id))",
							dataSource);
					System.out.println("Creating table " + tableName);
				}

				boolean historyTableExists = JDBCUtil.executeQueryAndReturnSingleLong(dataSource,
						"SELECT count(*) FROM information_schema.TABLES WHERE  (TABLE_NAME = ?) and (TABLE_SCHEMA = ?)",
						tableName + "_history", schema) == 1;
				if (!historyTableExists) {
					JDBCUtil.executeUpdate("create table " + tableName + "_history"
							+ "(id int(11), date datetime, changes longtext, stack longtext)", dataSource);
					JDBCUtil.executeUpdate("alter table " + tableName + "_history" + " add index id(id)", dataSource);
					JDBCUtil.executeUpdate("alter table " + tableName + "_history" + " add index date(date)",
							dataSource);
					System.out.println("Creating history table " + tableName + "_history");
				}
				ResultSet rst = JDBCUtil.executeQuery(connection, "describe " + tableName);
				Map<String, String> columnsToTypes = new THashMap<>();
				List<String> indexes = new ArrayList<>();
				while (rst.next()) {
					columnsToTypes.put(rst.getString("Field"), rst.getString("Type"));
					if (!rst.getString("Key").trim().isEmpty()) {
						indexes.add(rst.getString("Field"));
					}

				}
				for (String columnName : ReflectionUtils.getFieldNames(clazz)) {
					Class fieldType = ReflectionUtils.getFieldType(clazz, columnName);
					String columnType;

					if (fieldType == long.class || fieldType == int.class || fieldType == Integer.class
							|| fieldType == Long.class) {
						columnType = "int(11)";
					} else if (fieldType == BigDecimal.class) {
						columnType = "decimal(10,2)";
					} else if (fieldType == Date.class) {
						columnType = "datetime";
					} else {
						columnType = "varchar(500)";
					}

					if (!columnsToTypes.containsKey(columnName)) {
						System.out.println("Adding column " + columnName + " to table " + tableName);
						// Column doesnt exist
						JDBCUtil.executeUpdate(
								"alter table " + tableName + " add column " + columnName + " " + columnType,
								dataSource);
					} else if (!columnsToTypes.get(columnName).toLowerCase().contains(columnType)) {
						System.out.println(
								"Modifying column " + columnName + ":" + columnType + " to table " + tableName);
						JDBCUtil.executeUpdate(
								"alter table " + tableName + " modify column " + columnName + " " + columnType,
								dataSource);
					}
				}
				for (String indexedColumn : ReflectionUtils.getIndexedColumns(clazz)) {
					if (!indexes.contains(indexedColumn)) {
						System.out.println("Adding index " + indexedColumn + " to table " + tableName);
						JDBCUtil.executeUpdate(
								"alter table " + tableName + " add index " + indexedColumn + "(" + indexedColumn + ")",
								dataSource);
					}
				}

				cache.flush();

			}
			List<Class<MAGAAssociation>> associationsClasses = new ArrayList(
					reflections.getSubTypesOf(MAGAAssociation.class));
			List<MAGAAssociation> associations = new ArrayList<>();
			for (Class<MAGAAssociation> clazz : associationsClasses) {
				associations.add(clazz.newInstance());
			}
			for (MAGAAssociation association : associations) {
				if (association.type() == MAGAAssociation.ONE_TO_MANY) {
					String tableName = association.class2().getSimpleName();
					ResultSet rst = JDBCUtil.executeQuery(connection, "describe " + tableName);
					Map<String, String> columnsToTypes = new THashMap<>();
					List<String> indexes = new ArrayList<>();
					while (rst.next()) {
						columnsToTypes.put(rst.getString("Field"), rst.getString("Type"));
						if (!rst.getString("Key").trim().isEmpty()) {
							indexes.add(rst.getString("Field"));
						}

					}
					String columnName = association.class2Column();
					if (!columnsToTypes.containsKey(columnName)) {
						System.out.println("Adding join column " + columnName + " on " + tableName);
						JDBCUtil.executeUpdate("alter table " + tableName + " add column " + columnName + " int(11)",
								dataSource);
					}
					if (!indexes.contains(columnName)) {
						System.out.println("Adding index to join column " + columnName + " on " + tableName);
						JDBCUtil.executeUpdate(
								"alter table " + tableName + " add index " + columnName + "(" + columnName + ")",
								dataSource);
					}
				} else {
					String tableName = association.class1().getSimpleName() + "_to_"
							+ association.class2().getSimpleName();
					boolean tableExists = JDBCUtil.executeQueryAndReturnSingleLong(dataSource,
							"SELECT count(*) FROM information_schema.TABLES WHERE  (TABLE_NAME = ?) and (TABLE_SCHEMA = ?)", tableName, schema) == 1;
					if (!tableExists) {
						String col1 = association.class1().getSimpleName();
						String col2 = association.class2().getSimpleName();
						System.out.println(
								"create table " + tableName + " (" + col1 + " int(11), " + col2 + "  int(11))");
						JDBCUtil.executeUpdate(
								"create table " + tableName + " (" + col1 + " int(11), " + col2 + "  int(11))",
								dataSource);
						JDBCUtil.executeUpdate(
								"alter table " + tableName + " add index " + col1 + "(" + col1 + "," + col2 + ")",
								dataSource);
						JDBCUtil.executeUpdate(
								"alter table " + tableName + " add index " + col2 + "(" + col2 + "," + col1 + ")",
								dataSource);
						System.out.println("Creating join table " + tableName);
					}
				}
			}

		} catch (Exception e) {
			throw new MAGAException(e);
		} finally {
			JDBCUtil.closeConnection(connection);
		}
	}
}