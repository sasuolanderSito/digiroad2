package fi.liikennevirasto.digiroad2.oracle.collections;

import oracle.jdbc.OracleConnection;
import oracle.jdbc.OraclePreparedStatement;
import oracle.sql.ARRAY;
import scala.Int;
import scala.Long;
import scala.Double;
import scala.Tuple6;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class OracleArray {
    private static List<Tuple6<Long, Long, Int, Int, Double, Double>> linearAssetsByRoadLinkIds(List ids, Connection connection, String query) throws SQLException {
        OracleConnection oracleConnection = (OracleConnection) connection;
        ARRAY oracleArray = oracleConnection.createARRAY("ROAD_LINK_VARRAY", ids.toArray());
        try (PreparedStatement statement = oracleConnection.prepareStatement(query)) {
            OraclePreparedStatement oraclePreparedStatement = (OraclePreparedStatement) statement;
            oraclePreparedStatement.setArray(1, oracleArray);
            try (ResultSet resultSet = oraclePreparedStatement.executeQuery()) {
                ArrayList<Tuple6<Long, Long, Int, Int, Double, Double>> assetLinks = new ArrayList<Tuple6<Long, Long, Int, Int, Double, Double>>();
                while (resultSet.next()) {
                    long id = resultSet.getLong(1);
                    long roadLinkId = resultSet.getLong(2);
                    int sideCode = resultSet.getInt(3);
                    int limitValue = resultSet.getInt(4);
                    double startMeasure = resultSet.getDouble(5);
                    double endMeasure = resultSet.getDouble(6);
                    assetLinks.add(new Tuple6(id, roadLinkId, sideCode, limitValue, startMeasure, endMeasure));
                }
                return assetLinks;
            }
        }
    }

    public static List<Tuple6<Long, Long, Int, Int, Double, Double>> fetchAssetLinksByRoadLinkIds(List ids, Connection connection) throws SQLException {
        String query = "SELECT a.id, pos.road_link_id, pos.side_code, e.name_fi as speed_limit, pos.start_measure, pos.end_measure " +
                "FROM ASSET a " +
                "JOIN ASSET_LINK al ON a.id = al.asset_id " +
                "JOIN LRM_POSITION pos ON al.position_id = pos.id " +
                "JOIN PROPERTY p ON a.asset_type_id = p.asset_type_id AND p.public_id = 'rajoitus' " +
                "JOIN SINGLE_CHOICE_VALUE s ON s.asset_id = a.id AND s.property_id = p.id " +
                "JOIN ENUMERATED_VALUE e ON s.enumerated_value_id = e.id " +
                "WHERE a.asset_type_id = 20 AND pos.road_link_id IN (SELECT COLUMN_VALUE FROM TABLE(?))";
        return linearAssetsByRoadLinkIds(ids, connection, query);
    }

    public static List<Tuple6<Long, Long, Int, Int, Double, Double>> fetchTotalWeightLimitsByRoadLinkIds(List ids, Connection connection) throws SQLException {
        String query =
                "select segm_id, tielinkki_id, puoli, arvo, alkum, loppum" +
                        "  from segments" +
                        "  where tyyppi = 22" +
                        "    and tielinkki_id in (SELECT COLUMN_VALUE FROM TABLE(?))";
        return linearAssetsByRoadLinkIds(ids, connection, query);
    }
}
