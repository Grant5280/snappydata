/*
 * Copyright (c) 2018 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */

package io.snappydata.hydra.externaltables

import java.io.{FileOutputStream, PrintWriter, File}

import com.typesafe.config.Config
import org.apache.spark.SparkContext
import org.apache.spark.sql._

class ExternalTablesAPINorthWind extends SnappySQLJob{
  override def isValidJob(sc: SnappySession, config: Config): SnappyJobValidation = SnappyJobValid()

  override def runSnappyJob(snappySession: SnappySession, jobConfig: Config): Any = {
    //  scalastyle:off println
    println("Started the CSV -> JSON and CSV -> AVRO conversion using external tables...")
    val snc : SnappyContext = snappySession.sqlContext
    val sc = SparkContext.getOrCreate()
    snc.setConf("spark.sql.crossJoin.enabled", "true")
    val sqlContext = SQLContext.getOrCreate(sc)
    val dataFileLocation = jobConfig.getString("dataFilesLocation")
    def getCurrentDirectory = new java.io.File(".").getCanonicalPath()
    val spark : SparkSession = SparkSession.builder().getOrCreate()
    spark.conf.set("spark.sql.crossJoin.enabled", "true")
    val outputFile = "ValidateExternalTable_" +  System.currentTimeMillis()
    val pw : PrintWriter = new PrintWriter(new FileOutputStream(new File(outputFile), true))
    val printDFContent : Boolean = false

    snc.sql("CREATE SCHEMA NW;")

    loadDataFromSourceAndRunSelectQueryThenDropTable(snc, "csv",
       dataFileLocation, "employees.csv", "NW.Employees")
    loadDataFromSourceAndRunSelectQueryThenDropTable(snc, "csv",
      dataFileLocation, "categories.csv", "NW.Categories")
    loadDataFromSourceAndRunSelectQueryThenDropTable(snc, "csv",
      dataFileLocation, "customers.csv", "NW.Customers")
    loadDataFromSourceAndRunSelectQueryThenDropTable(snc, "csv",
      dataFileLocation, "employee-territories.csv", "NW.EmployeeTerritories")
    loadDataFromSourceAndRunSelectQueryThenDropTable(snc, "csv",
      dataFileLocation, "order-details.csv", "NW.OrderDetails")
    loadDataFromSourceAndRunSelectQueryThenDropTable(snc, "csv",
      dataFileLocation, "orders.csv", "NW.Orders")
    loadDataFromSourceAndRunSelectQueryThenDropTable(snc, "csv",
      dataFileLocation, "products.csv", "NW.Products")
    loadDataFromSourceAndRunSelectQueryThenDropTable(snc, "csv",
      dataFileLocation, "regions.csv", "NW.Regions")
    loadDataFromSourceAndRunSelectQueryThenDropTable(snc, "csv",
      dataFileLocation, "shippers.csv", "NW.Shippers")
    loadDataFromSourceAndRunSelectQueryThenDropTable(snc, "csv",
      dataFileLocation, "suppliers.csv", "NW.Suppliers")
    loadDataFromSourceAndRunSelectQueryThenDropTable(snc, "csv",
      dataFileLocation, "territories.csv", "NW.Territories")

    // Create DataFrame From External Tabels
    val sncOrderDetailsDF : DataFrame = snc.createExternalTable("NW.OrderDetails",
      "csv", Map ("path"-> (dataFileLocation + "order-details.csv") ,
        "BUCKETS"-> "8", "header" -> "true", "inferSchema"->"true"))
      .toDF("OrderId", "PrdId", "UnitPrice", "Qty", "Discount")
    val sparkOrderDetailsDF : DataFrame = spark.read.format("csv")
      .option("header", "true").option("inferSchema", "true")
      .load(dataFileLocation + "order-details.csv")

    val sncOrdersDF : DataFrame = snc.createExternalTable("NW.Orders", "csv",
      Map ("path"-> (dataFileLocation + "orders.csv") , "BUCKETS"->"8",
        "header" -> "true", "inferSchema"->"true"))
      .toDF("OrderId", "CustId", "EId", "OrderDt", "ReqDt", "ShippedDt",
      "ShippedVia", "Freight", "ShipName", "ShipAddr", "ShipCity", "ShipRegion",
      "ShipPostalCode", "ShipCountry")
    val sparkOrdersDF : DataFrame = spark.read.format("csv")
      .option("header", "true").option("inferSchema", "true")
      .load(dataFileLocation + "orders.csv")

    val sncEmpDF : DataFrame = snc.createExternalTable( "NW.Employees", "csv",
      Map ("path"->(dataFileLocation + "employees.csv") , "BUCKETS"->"8",
        "header" -> "true", "inferSchema"->"true"))
      .toDF("EmpID", "LastName", "FirstName", "Title", "TitleOfCourtesy",
            "BirthDt", "HireDt", "Addr", "City", "Region", "PostalCd", "Country",
            "HomePhone", "Extension", "Photo", "Notes", "ReportsTo", "Photopath")
    val sparkEmpDF : DataFrame = spark.read.format( "csv")
      .option("header", "true").option("inferSchema", "true")
      .load(dataFileLocation + "employees.csv")

    val sncCategoriesDF : DataFrame = snc.createExternalTable("NW.Categories" , "csv",
      Map("path" -> (dataFileLocation + "categories.csv") , "BUCKETS" -> "8",
        "header" -> "true", "inferSchema"->"true"))
      .toDF("CategoryID", "CategoryName", "Description", "Picture")
    val sparkCategoriesDF : DataFrame = spark.read.format("csv")
      .option("header", "true").option("inferSchema", "true")
      .load(dataFileLocation + "categories.csv")

    val sncCustomerDF : DataFrame = snc.createExternalTable("NW.Customers" , "csv",
      Map("path" -> (dataFileLocation + "customers.csv") , "BUCKETS" -> "8",
        "header" -> "true", "inferSchema"->"true"))
      .toDF("CustID", "CompanyName", "ContactName", "ContactTitle", "Addr", "City",
        "Region", "PostalCode", "Country", "Phone", "Fax")
    val sparkCustomerDF : DataFrame = spark.read.format("csv")
      .option("header", "true").option("inferSchema", "true")
      .load(dataFileLocation + "customers.csv")

    val sncEmpTerDF : DataFrame = snc.createExternalTable("NW.EmployeeTerritories" , "csv",
      Map("path" -> (dataFileLocation + "employee-territories.csv") , "BUCKETS" -> "8",
        "header" -> "true", "inferSchema"->"true"))
      .toDF("EmpID", "TerritoryID")
    val sparkEmpTerDF : DataFrame = spark.read.format("csv")
      .option("header", "true").option("inferSchema", "true")
      .load(dataFileLocation + "employee-territories.csv")

    val sncProductDF : DataFrame = snc.createExternalTable("NW.Products" , "csv",
      Map("path" -> (dataFileLocation + "products.csv") , "BUCKETS" -> "8",
        "header" -> "true", "inferSchema"->"true"))
      .toDF("ProdID", "ProductName", "SupplierID", "CategoryID", "QtyPerUnit", "UnitPrice",
        "UnitsInStock", "UnitsOnOrder", "ReorderLevel", "Discontinued")
    val sparkProductDF : DataFrame = spark.read.format("csv")
      .option("header", "true").option("inferSchema", "true")
      .load(dataFileLocation + "products.csv")

    val sncRegionDF : DataFrame = snc.createExternalTable("NW.Regions" , "csv",
      Map("path" -> (dataFileLocation + "regions.csv") , "BUCKETS" -> "8",
        "header" -> "true", "inferSchema"->"true"))
      .toDF("RegionID", "RegionDescription")
    val sparkRegionDF : DataFrame = spark.read.format("csv")
      .option("header", "true").option("inferSchema", "true")
      .load(dataFileLocation + "regions.csv")

    val sncShipperDF : DataFrame = snc.createExternalTable("NW.Shippers" , "csv",
      Map("path" -> (dataFileLocation + "shippers.csv") , "BUCKETS" -> "8",
        "header" -> "true", "inferSchema"->"true"))
      .toDF("ShipperID", "CompanyName", "Phone")
    val sparkShipperDF : DataFrame = spark.read.format("csv")
      .option("header", "true").option("inferSchema", "true")
      .load(dataFileLocation + "shippers.csv")

    val sncSupplierDF : DataFrame = snc.createExternalTable("NW.Suppliers", "csv" ,
      Map("path" -> (dataFileLocation + "suppliers.csv") , "BUCKETS" -> "8",
        "header" -> "true", "inferSchema"->"true"))
      .toDF("SupplierID", "CompanyName", "ContactName", "ContactTitle", "Addr", "City",
        "Region", "PostalCode", "Country", "Phone", "Fax", "HomePage")
    val sparkSupplierDF : DataFrame = spark.read.format("csv")
      .option("header", "true").option("inferSchema", "true")
      .load(dataFileLocation + "suppliers.csv")

    val sncTerrDF : DataFrame = snc.createExternalTable("NW.Territories", "csv",
      Map("path" -> (dataFileLocation + "territories.csv") , "BUCKETS" -> "8",
        "header" -> "true", "inferSchema"->"true"))
      .toDF("TerritoryID", "TerritoryDescription", "RegionID")
    val sparkTerrDF : DataFrame = spark.read.format("csv")
      .option("header", "true").option("inferScheam", "true")
      .load(dataFileLocation + "territories.csv")

    /* <1> SELECT TitleOfCourtesy, FirstName, LastName FROM Employees
           WHERE TitleOfCourtesy IN ('Ms.','Mrs.'); */
    val snc_namesWithTOC = sncEmpDF.select("TitleOfCourtesy", "FirstName", "LastName")
      .where(sncEmpDF("TitleOfCourtesy").isin("Ms.", "Mrs."))
    val spark_namesWithTOC = sparkEmpDF.select("TitleOfCourtesy", "FirstName", "LastName")
        .where(sparkEmpDF("TitleOfCourtesy").isin("Ms.", "Mrs."))
    if(printDFContent) {
      println("***** <1>snc_namesWithTOC : " + snc_namesWithTOC.show())
      println("##### <1>spark_namesWithTOC : " + spark_namesWithTOC.show())
    }
    val df1 = snc_namesWithTOC.except(spark_namesWithTOC)
    if(!(df1.count() == 0)) {
      pw.write("SELECT TitleOfCourtesy, FirstName, LastName FROM Employees " +
        "WHERE TitleOfCourtesy IN ('Ms.','Mrs.') --> failed")
      pw.println()
      pw.write("Difference : " + df1.show())
      pw.println()
    }
    if(df1.count() == 0) {
      pw.write("SELECT TitleOfCourtesy, FirstName, LastName FROM Employees " +
        "WHERE TitleOfCourtesy IN ('Ms.','Mrs.') --> passed")
    }

    /*  <2> SELECT FirstName, LastName FROM Employees; */
    val snc_names = sncEmpDF.select("FirstName" , "LastName")
    val spark_names = sparkEmpDF.select("FirstName", "LastName")
    if(printDFContent) {
      println("***** <2>snc_names : " + snc_names.show())
      println("##### <2>spark_names : " + spark_names.show())
    }
    val df2 = snc_names.except(spark_names)
    if(!(df2.count() == 0)) {
      pw.write("SELECT FirstName, LastName FROM Employees --> failed")
      pw.println()
      pw.write("Difference : " + df2.show())
      pw.println()
    }
    if(df2.count() == 0) {
      pw.write("SELECT FirstName, LastName FROM Employees --> passed")
      pw.println()
    }

    /*  <3> SELECT FirstName, LastName FROM Employees ORDER BY LastName; */
    val snc_namesSortByLastName = sncEmpDF.select("FirstName", "LastName").orderBy(sncEmpDF("LastName").desc)
    val spark_namesSortByLastName = sparkEmpDF.select("FirstName", "LastName")
      .orderBy(sparkEmpDF("LastName").desc)
    if(printDFContent) {
      println("***** <3>snc_namesSortByLastName : " + snc_namesSortByLastName.show())
      println("##### <3>spark_namesSortByLastName : " + spark_namesSortByLastName.show())
    }
    val df3 = snc_namesSortByLastName.except(spark_namesSortByLastName)
    if(!(df3.count() == 0)) {
      pw.write("SELECT FirstName, LastName FROM Employees " +
        "ORDER BY LastName --> failed")
      pw.println()
      pw.write("Difference : " + df3.show())
      pw.println()
    }
    if(df3.count() == 0) {
      pw.write("SELECT FirstName, LastName FROM Employees " +
        "ORDER BY LastName --> passed")
      pw.println()
    }


    /* <4> SELECT Title, FirstName, LastName FROM Employees WHERE Title = 'Sales Representative'; */
    val snc_salesRep = sncEmpDF.select(("Title"), "FirstName", "LastName").filter(sncEmpDF("Title") === "Sales Representative")
    val spark_SalesRep = sparkEmpDF.select("Title", "FirstName", "LastName")
        .filter((sparkEmpDF("Title") === "Sales Representative"))
    if(printDFContent) {
      println("***** <4>snc_salesRep : " + snc_salesRep.show())
      println("##### <4>spark_salesRep : " + spark_SalesRep.show())
    }
    val df4 = snc_salesRep.except(spark_SalesRep)
    if(!(df4.count() == 0)) {
      pw.write("SELECT Title, FirstName, LastName FROM Employees " +
        "WHERE Title = 'Sales Representative' --> failed")
      pw.println()
      pw.write("Difference : " + df4.show())
      pw.println()
    }
    if(df4.count() == 0) {
      pw.write("SELECT Title, FirstName, LastName FROM Employees " +
        "WHERE Title = 'Sales Representative' --> passed")
      pw.println()
    }

    /*  <5> SELECT FirstName, LastName FROM Employees WHERE Title <> 'Sales Representative';
    //  TODO : Test the where(String) or filter("String) condition
    //  val snc_titleOtherThanSalsRep = sncEmpDF.select("FirstName" , "LastName")
    // .where("Title =!= Sales Representative") */
    val snc_titleOtherThanSalsRep = sncEmpDF.select("FirstName" , "LastName").filter(sncEmpDF("Title") =!= "Sales Representative")
    val spark_titleOtherThanSalsRep = sparkEmpDF.select("FirstName", "LastName")
        .filter(sparkEmpDF("Title") =!= "Sales Representative")
    if(printDFContent) {
      println("***** <5>snc_titleOtherThanSalsRep : " + snc_titleOtherThanSalsRep.show())
      println("##### <5>spark_titleOtherThanSalsRep : " + spark_titleOtherThanSalsRep.show())
    }
    val df5 = snc_titleOtherThanSalsRep.except(spark_titleOtherThanSalsRep)
    if(!(df5.count() == 0)) {
      pw.write("SELECT Title, FirstName, LastName FROM Employees " +
        "WHERE Title = 'Sales Representative' --> failed")
      pw.println()
      pw.write("Difference : " + df5.show())
      pw.println()
    }
    if(df5.count() == 0) {
      pw.write("SELECT Title, FirstName, LastName FROM Employees " +
        "WHERE Title = 'Sales Representative' --> passed")
      pw.println()
    }

    /* <6> SELECT FirstName, LastName FROM Employees WHERE LastName >= 'N'
           ORDER BY LastName DESC; */
    val snc_EmpNameDesc = sncEmpDF.select( "FirstName", "LastName")
      .where("LastName >= 'N'").orderBy("LastName")
    val spark_EmpNameDesc = sparkEmpDF.select("FirstName", "LastName")
        .where("LastName >= 'N'").orderBy("LastName")
    if(printDFContent) {
      println("***** <6>snc_empName : " + snc_EmpNameDesc.show())
      println("##### <6>spark_empName : " + spark_EmpNameDesc.show())
    }
    val df6 = snc_EmpNameDesc.except(spark_EmpNameDesc)
    if(!(df6.count() == 0)) {
      pw.write("SELECT FirstName, LastName FROM Employees " +
        "WHERE LastName >= 'N' ORDER BY LastName DESC --> failed")
      pw.println()
      pw.write("Difference : " + df6.show())
      pw.println()
    }
    if(df6.count() == 0) {
      pw.write("SELECT FirstName, LastName FROM Employees " +
        "WHERE LastName >= 'N' ORDER BY LastName DESC --> passed")
      pw.println()
    }

    /*  <7> SELECT OrderID, Freight, Freight * 1.1 AS FreightTotal FROM Orders
    WHERE Freight >= 500; */
    val snc_FreightTotal = sncOrdersDF("Freight") * 1.1
    val snc_AddFreightTotal = sncOrdersDF.withColumn("FreightTotal", snc_FreightTotal)
    val snc_Freightgeq500 = snc_AddFreightTotal.select("OrderId", "Freight", "FreightTotal")
      .where(snc_AddFreightTotal("Freight").geq(500))
    val spark_FreightTotal = sparkOrdersDF("Freight") * 1.1
    val spark_AddFreightTotal = sparkOrdersDF.withColumn("FreightTotal", spark_FreightTotal)
    val spark_Freightgeq500 = spark_AddFreightTotal.select("OrderID", "Freight" , "FreightTotal")
        .where(spark_AddFreightTotal("Freight").geq(500))
    if(printDFContent) {
      println("***** <7>snc_freightgeq500 : " + snc_Freightgeq500.show())
      println("##### <7>spark_freightgeq500 : " + spark_Freightgeq500.show())
    }
    val df7 = snc_Freightgeq500.except(spark_Freightgeq500)
    if(!(df7.count() == 0)) {
      pw.write("SELECT OrderID, Freight, Freight * 1.1 AS FreightTotal " +
        "FROM Orders WHERE Freight >= 500 --> failed")
      pw.println()
      pw.write("Difference : " + df7.show())
      pw.println()
    }
    if(df7.count() == 0) {
      pw.write("SELECT OrderID, Freight, Freight * 1.1 AS FreightTotal " +
        "FROM Orders WHERE Freight >= 500 --> passed")
      pw.println()
    }

    /* <8> SELECT SUM(Quantity) AS TotalUnits FROM Order_Details WHERE ProductID=3; */
    import org.apache.spark.sql.functions._
    val snc_TotalUnits = sncOrderDetailsDF.filter(sncOrderDetailsDF("PrdId").equalTo(3))
      .agg(sum("Qty").alias("TotalUnits"))
    val spark_TotalUnits = sparkOrderDetailsDF.filter(sparkOrderDetailsDF("ProductID").equalTo(3))
        .agg(sum("Quantity").alias("TotalUnits"))
    if(printDFContent) {
      println("***** <8>snc_totalUnits : " + snc_TotalUnits.show())
      println("##### <8>spark_totalUnits : " + spark_TotalUnits.show())
    }
    // Print the result in file

    /* <9> SELECT COUNT(DISTINCT City) AS NumCities FROM Employees; */
    val snc_DistinctCity = sncEmpDF.select("City").distinct().withColumnRenamed("City", "NumCities")
    val spark_DistinctCity = sparkEmpDF.select("City").distinct()
      .withColumnRenamed("City", "NumCities")
    val snc_DistinctCityCount = sncEmpDF.agg(countDistinct("City"))
    val spark_DistinctCityCount = sparkEmpDF.agg(countDistinct("City"))
    if(printDFContent) {
      println("***** <9.1>snc_DistinctCity : " + snc_DistinctCity.show())
      println("##### <9.1>spark_DistinctCity : " + spark_DistinctCity.show())
      println("***** <9.2>snc_DistinctCityCount : " + snc_DistinctCityCount.show())
      println("##### <9.2>spark_DistinctCityCount : " + spark_DistinctCityCount.show())
    }
    val df9_1 = snc_DistinctCity.except(spark_DistinctCity)
    if(!(df9_1.count() == 0)) {
      pw.write("SELECT COUNT(DISTINCT City) AS NumCities FROM Employees --> failed")
      pw.println()
      pw.write("Difference : " + df9_1.show())
      pw.println()
    }
    if(df9_1.count() == 0) {
      pw.write("SELECT COUNT(DISTINCT City) AS NumCities FROM Employees --> passed")
      pw.println()
    }


    /* <10> SELECT CONCAT(FirstName, ' ', LastName) FROM Employees; */
    val snc_Name = sncEmpDF.select(concat_ws(" ", col("FirstName"), col("LastName")))
    val snc_Name1 = sncEmpDF.select(concat(col("FirstName"), lit(","), col("LastName")))
    val spark_Name = sparkEmpDF.select(concat_ws(" ", col("FirstName"), col("LastName")))
    val spark_Name1 = sparkEmpDF.select(concat(col("FirstName"), lit(","), col("LastName")))
    if(printDFContent) {
      println("***** <10.1>snc_Name : " + snc_Name.show())
      println("##### <10.1>spark_Name : " + spark_Name.show())
      println("***** <10.2>snc_Name1 : " + snc_Name1.show())
      println("##### <10.2>spark_Name1 : " + spark_Name1.show())
    }
    val df10_1 = snc_Name.except(spark_Name)
    if(!(df10_1.count() == 0)) {
      pw.write("SELECT CONCAT(FirstName,' ',LastName) FROM Employees --> failed")
      pw.println()
      pw.write("Difference : " + df10_1.show())
      pw.println()
    }
    if(df10_1.count() == 0) {
      pw.write("SELECT CONCAT(FirstName,' ',LastName) FROM Employees --> passed")
      pw.println()
    }
    val df10_2 = snc_Name1.except(spark_Name1)
    if(!(df10_2.count() == 0)) {
      pw.write("SELECT CONCAT(FirstName,',',LastName) FROM Employees --> failed")
      pw.println()
      pw.write("Difference : " + df10_2.show())
      pw.println()
    }
    if(df10_2.count() == 0) {
      pw.write("SELECT CONCAT(FirstName,',',LastName) FROM Employees --> passed")
      pw.println()
    }

    /* <11> SELECT count(*) FROM orders FULL JOIN order_details; */
    val snc_FullJoinCnt = sncOrdersDF.crossJoin(sncOrderDetailsDF)
    val spark_FullJoinCnt = sparkOrdersDF.crossJoin(sparkOrderDetailsDF)
    if(printDFContent) {
      println("***** <11>snc_FullJoinCount : " + snc_FullJoinCnt.count())
      println("##### <11>spark_FullJoinCount : " + spark_FullJoinCnt.count())
    }
    val df11 = snc_FullJoinCnt.except(spark_FullJoinCnt)
    if(!(df11.count() == 0)) {
      pw.write("SELECT count(*) FROM orders FULL JOIN order_details --> failed")
      pw.println()
      pw.write("Difference : " + df11.show())
      pw.println()
    }
    if(df11.count() == 0) {
      pw.write("SELECT count(*) FROM orders FULL JOIN order_details --> passed")
      pw.println()
    }

    /* <12> SELECT OrderDate, count(1) from Orders group by OrderDate order by OrderDate asc; */
    import org.apache.spark.sql.functions.{count, lit}
    val snc_dateWiseOrderCountASC = sncOrdersDF.select(col("OrderDt")).groupBy(col("OrderDt"))
        .agg(count(lit(1))).withColumnRenamed("count(1)", "DateWiseCountInAscOrder")
        .orderBy(asc("OrderDt"))
    val spark_dateWiseOrderCountASC = sparkOrdersDF.select(col("OrderDate"))
      .groupBy(col("OrderDate"))
      .agg(count(lit(1))).withColumnRenamed("count(1)", "DateWiseCountInAscOrder")
      .orderBy(asc("OrderDate"))
    if(printDFContent) {
      println("***** <12>snc_dateWiseOrderCountASC : " + snc_dateWiseOrderCountASC.show(480))
      println("##### <12>spark_dateWiseOrderCountASC : " + spark_dateWiseOrderCountASC.show(480))
    }
    val df12 = snc_dateWiseOrderCountASC.except(spark_dateWiseOrderCountASC)
    if(!(df12.count() == 0)) {
      pw.write("SELECT OrderDate, count(1) from Orders group by OrderDate " +
                "order by OrderDate asc --> failed")
      pw.println()
      pw.write("Difference : " + df12.show())
      pw.println()
    }
    if(df12.count() == 0) {
      pw.write("SELECT OrderDate, count(1) from Orders group by OrderDate " +
        "order by OrderDate asc --> passed")
      pw.println()
    }

    /* <13> SELECT OrderDate, count(1) from Orders group by OrderDate order by OrderDate; */
    val snc_dateWiseOrderCnt = sncOrdersDF.select(col("OrderDt")).groupBy(col("OrderDt"))
        .agg(count(lit(1))).withColumnRenamed("count(1)", "DateWiseCount")
        .orderBy(col("OrderDt"))
    val spark_dateWiseOrderCnt = sparkOrdersDF.select(col("OrderDate")).groupBy("OrderDate")
        .agg(count(lit(1))).withColumnRenamed("count(1)", "DateWiseCount")
        .orderBy(col("OrderDate"))
    if(printDFContent) {
      println("***** <13>snc_dateWiseOrderCnt : " + snc_dateWiseOrderCnt.show(480))
      println("##### <13>spark_dateWiseOrderCnt : " + spark_dateWiseOrderCnt.show(480))
    }
    val df13 = snc_dateWiseOrderCnt.except(spark_dateWiseOrderCnt)
    if(!(df13.count() == 0)) {
      pw.write("SELECT OrderDate, count(1) from Orders group by OrderDate " +
        "order by OrderDate --> failed")
      pw.println()
      pw.write("Difference : " + df13.show())
      pw.println()
    }
    if(df13.count() == 0) {
      pw.write("SELECT OrderDate, count(1) from Orders group by OrderDate " +
        "order by OrderDate --> passed")
      pw.println()
    }

    /* <14> SELECT FirstName, LastName FROM Employees WHERE LastName >= 'N'; */
    val snc_EmpName = sncEmpDF.select(col("FirstName"), col("LastName"))
        .where(col("LastName").geq("N"))
    val spark_EmpName = sparkEmpDF.select(col("FirstName"), col("LastName"))
        .where(col("LastName").geq("N"))
    if(printDFContent) {
      println("***** <14>snc_EmpName : " + snc_EmpName.show())
      println("##### <14>spark_EmpName " + spark_EmpName.show())
    }
    val df14 = snc_EmpName.except(spark_EmpName)
    if(!(df14.count() == 0)) {
      pw.write("SELECT FirstName, LastName FROM Employees WHERE LastName >= 'N' --> failed")
      pw.println()
      pw.write("Difference : " + df14.show())
      pw.println()
    }
    if(df14.count() == 0) {
      pw.write("SELECT FirstName, LastName FROM Employees WHERE LastName >= 'N' --> passed")
      pw.println()
    }

    /* <15> SELECT FirstName, LastName FROM Employees WHERE Region IS NULL; */
    val snc_EmpNameWhereRegIsNull = sncEmpDF.select(col("FirstName"), col("LastName"))
        .where(col("Region").equalTo("NULL"))
    val spark_EmpNameWhereRegIsNull = sparkEmpDF.select(col("FirstName"), col("LastName"))
        .where(col("Region").equalTo("NULL"))
    if(printDFContent) {
      println("***** <15>snc_EmpNameWhereRegIsNull : " + snc_EmpNameWhereRegIsNull.show())
      println("##### <15>spark_EmpNameWhereRegIsNull : " + spark_EmpNameWhereRegIsNull.show())
    }
    val df15 = snc_EmpNameWhereRegIsNull.except(spark_EmpNameWhereRegIsNull)
    if(!(df15.count() == 0)) {
      pw.write("SELECT FirstName, LastName FROM Employees WHERE Region IS NULL --> failed")
      pw.println()
      pw.write("Difference : " + df15.show())
      pw.println()
    }
    if(df15.count() == 0) {
      pw.write("SELECT FirstName, LastName FROM Employees WHERE Region IS NULL --> passed")
      pw.println()
    }

    /* <16> SELECT Title, FirstName, LastName FROM Employees ORDER BY 1,3; */
    val snc_EmpNameOrderByColumnPos = sncEmpDF.select(col("Title"), col("FirstName"), col("LastName"))
      .orderBy(col("Title"), col("LastName"))
    val spark_EmpNameOrderByColumnPos = sparkEmpDF.select(
    col("Title"), col("FirstName"), col("LastName"))
        .orderBy(col("Title"), col("LastName"))
    if(printDFContent) {
      println("***** <16>snc_EmpNameOrderByColumnPos : " +  snc_EmpNameOrderByColumnPos.show())
      println("##### <16>spark_EmpNameOrderByColumnPos : " + spark_EmpNameOrderByColumnPos.show())
    }
    val df16 = snc_EmpNameOrderByColumnPos.except(spark_EmpNameOrderByColumnPos)
    if(!(df16.count() == 0)) {
      pw.write("SELECT Title, FirstName, LastName FROM Employees ORDER BY 1,3 --> failed")
      pw.println()
      pw.write("Difference : " + df16.show())
      pw.println()
    }
    if(df16.count() == 0) {
      pw.write("SELECT Title, FirstName, LastName FROM Employees ORDER BY 1,3 --> passed")
      pw.println()
    }

    /* <17> SELECT Title, FirstName, LastName FROM Employees ORDER BY Title ASC, LastName DESC; */
    val snc_EmpNameOrderByTitleLastName = sncEmpDF.select(col("Title"), col("FirstName"), col("LastName"))
        .orderBy(asc("Title"), desc("LastName"))
    val spark_EmpNameOrderByTitleLastName = sparkEmpDF.select(col("Title"),
        col("FirstName"), col("LastName"))
        .orderBy(asc("Title"), desc("LastName"))
    if(printDFContent) {
      println("***** <17>snc_EmpNameOrderByTitleLastName : "
        + snc_EmpNameOrderByTitleLastName.show())
      println("##### <17>spark_EmpNameOrderByTitleLastName : "
        + spark_EmpNameOrderByTitleLastName.show())
    }
    val df17 = (snc_EmpNameOrderByTitleLastName.except(spark_EmpNameOrderByTitleLastName))
    if(!(df17.count() == 0)) {
      pw.write("SELECT Title, FirstName, LastName FROM Employees " +
               "ORDER BY Title ASC, LastName DESC --> failed")
      pw.println()
      pw.write("Difference : " + df17.show())
      pw.println()
    }
    if(df17.count() == 0) {
      pw.write("SELECT Title, FirstName, LastName FROM Employees " +
        "ORDER BY Title ASC, LastName DESC --> passed")
      pw.println()
    }

    /* Will add all the NorthWind Queries */
    pw.flush()
    pw.close()

    snc.dropTable("NW.Employees")
    snc.dropTable("NW.Categories")
    snc.dropTable("NW.Customers")
    snc.dropTable("NW.EmployeeTerritories")
    snc.dropTable("NW.OrderDetails")
    snc.dropTable("NW.Orders")
    snc.dropTable("NW.Products")
    snc.dropTable("NW.Regions")
    snc.dropTable("NW.Shippers")
    snc.dropTable("NW.Suppliers")
    snc.dropTable("NW.Territories")

    snc.sql("DROP SCHEMA NW;")
    println("ExternalTablesAPINorthWind completed.....") // Write it into file
  }

  def loadDataFromSourceAndRunSelectQueryThenDropTable(snc : SnappyContext, format : String,
                                                       dataFileLocation : String, file : String,
                                                       tableName : String) : Unit = {
    println("Read the data from source and run queries..., " +
      "File path is : " + dataFileLocation + file)
    println()
    val df : DataFrame = snc.createExternalTable(tableName, (dataFileLocation + file), "csv")
    val data = df.select("*")
    println("SELECT * FROM : " + tableName + data.show())
    snc.dropTable(tableName, true)
  }
}
