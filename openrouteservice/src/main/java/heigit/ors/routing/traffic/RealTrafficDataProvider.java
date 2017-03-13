/*|----------------------------------------------------------------------------------------------
 *|														Heidelberg University
 *|	  _____ _____  _____      _                     	Department of Geography		
 *|	 / ____|_   _|/ ____|    (_)                    	Chair of GIScience
 *|	| |  __  | | | (___   ___ _  ___ _ __   ___ ___ 	(C) 2014
 *|	| | |_ | | |  \___ \ / __| |/ _ \ '_ \ / __/ _ \	
 *|	| |__| |_| |_ ____) | (__| |  __/ | | | (_|  __/	Berliner Strasse 48								
 *|	 \_____|_____|_____/ \___|_|\___|_| |_|\___\___|	D-69120 Heidelberg, Germany	
 *|	        	                                       	http://www.giscience.uni-hd.de
 *|								
 *|----------------------------------------------------------------------------------------------*/

// Authors: M. Rylov

package heigit.ors.routing.traffic;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Logger;

import javax.xml.parsers.ParserConfigurationException;

import heigit.ors.routing.RoutingProfile;
import heigit.ors.routing.RoutingProfileLoadContext;
import heigit.ors.routing.RoutingProfilesCollection;
import heigit.ors.routing.configuration.RouteManagerConfiguration;
import heigit.ors.routing.configuration.RouteProfileConfiguration;
import heigit.ors.routing.configuration.TrafficInformationConfiguration;
import heigit.ors.routing.traffic.providers.TrafficInfoDataSource;
import heigit.ors.routing.traffic.providers.TrafficInfoDataSourceFactory;
import org.json.JSONException;
import org.json.JSONWriter;
import org.xml.sax.SAXException;

import com.graphhopper.storage.GraphStorage;
import com.graphhopper.util.Helper;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.geom.MultiLineString;
import com.vividsolutions.jts.geom.Point;
import com.vividsolutions.jts.index.quadtree.Quadtree;

import heigit.ors.util.DebugUtility;
import heigit.ors.util.FormatUtility;

public class RealTrafficDataProvider {
	public class UpdateTask extends TimerTask {

		private RealTrafficDataProvider m_provider;

		public UpdateTask(RealTrafficDataProvider provider) {
			m_provider = provider;
		}

		public void run() {
			m_provider.runUpdateEdges();
		}
	}

	private class RouteProfileTmcData {
		private HashMap<Integer, EdgeInfo> m_edges;
		private HashMap<Integer, AvoidEdgeInfo> m_avoidEdges;
		private List<Integer> m_blockedEdges;
		private HashMap<Integer, Integer> m_edgeIdsMap;
		private RoutingProfile m_routeProfile;

		public RouteProfileTmcData(RoutingProfile rp) {
			m_routeProfile = rp;
			m_edges = new HashMap<Integer, EdgeInfo>();
			m_avoidEdges = new HashMap<Integer, AvoidEdgeInfo>();
			m_blockedEdges = new ArrayList<Integer>();
			m_edgeIdsMap = new HashMap<Integer, Integer>();
		}

		public RoutingProfile getRouteProfile() {
			return m_routeProfile;
		}

		public List<Integer> getBlockedEdges() {
			return m_blockedEdges;
		}

		public HashMap<Integer, AvoidEdgeInfo> getAvoidEdges() {
			return m_avoidEdges;
		}

		public HashMap<Integer, EdgeInfo> getEdges() {
			return m_edges;
		}

		public HashMap<Integer, Integer> getEdgeIdsMap() {
			return m_edgeIdsMap;
		}

		public void setEdgeIdsMap(HashMap<Integer, Integer> edgeIdsMap) {
			m_edgeIdsMap = edgeIdsMap;
		}

		public void update(HashMap<Integer, EdgeInfo> edges, HashMap<Integer, AvoidEdgeInfo> avoidEdges,
				List<Integer> blockedEdges) {
			m_avoidEdges = avoidEdges;
			m_blockedEdges = blockedEdges;
			m_edges = edges;
		}
	}

	private class TmcUpdateInfo {
		private Date time;
		private List<TrafficFeatureInfo> features;
		private Quadtree quadTree;

		private TmcUpdateInfo(Date time, List<TrafficFeatureInfo> features) {
			this.time = time;
			this.features = features;
		}
		
		public Date getTime()
		{
			return time;
		}
		
		public List<TrafficFeatureInfo> getFeatures(Envelope env)
		{
			if (env == null)
				return features;
			
			if (quadTree == null)
				buildQuadTree();
			
			List<TrafficFeatureInfo> list = quadTree.query(env);
			List<TrafficFeatureInfo> result = new ArrayList<TrafficFeatureInfo>(list.size());
			
			for(TrafficFeatureInfo tfi : list)
			{
				Envelope fe = tfi.getEnvelope();
				if (fe != null && fe.intersects(env))
					result.add(tfi);
			}
			
			return result;
		}
		
		private void buildQuadTree()
		{
			quadTree = new Quadtree();
			for (TrafficFeatureInfo tfi : features) {
				if (tfi.getGeometry() instanceof LineString || tfi.getGeometry() instanceof MultiLineString) // TODO remove
					quadTree.insert(tfi.getGeometry().getEnvelopeInternal(), tfi);
			}
		}
	}

	private static RealTrafficDataProvider mInstance;

	private Logger logger = Logger.getLogger(RealTrafficDataProvider.class.getName());

	private RoutingProfile m_tmcRouteProfile;
	private TmcSegmentsCollection m_tmcSegments;
	private HashMap<Integer, RouteProfileTmcData> m_routeProfilesMap;
	private Timer m_timer;
	private boolean m_updateIsRunning = false;
	private boolean m_initialized;
	private TrafficInformationConfiguration m_config;
	private TmcUpdateInfo m_lastUpdateInfo;
	private TrafficLocationGraph m_locationGraph;
	private TrafficInfoDataSource m_tmcDatasource;
	private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
	
	public static synchronized RealTrafficDataProvider getInstance() {
		if (mInstance == null)
			mInstance = new RealTrafficDataProvider();

		return mInstance;
	}

	public RealTrafficDataProvider() {
		m_routeProfilesMap = new HashMap<Integer, RealTrafficDataProvider.RouteProfileTmcData>();
	}

	public void initialize(RouteManagerConfiguration rmc, RoutingProfilesCollection profiles) throws Exception {
		m_config = rmc.TrafficInfoConfig;

		// Proceeed only when we have a car profile.
		if (profiles.getCarProfiles().size() > 0) {

			RouteProfileConfiguration rpc = null;
			TmcUpdateInfo updateInfo = null;

			try
			{
				RoutingProfileLoadContext loadCntx = new RoutingProfileLoadContext();
				
				for (RoutingProfile rp : profiles.getCarProfiles()) {
					if (rp.useTrafficInformation() && !rp.isCHEnabled()) {
						if (rpc == null) {
							rpc = new RouteProfileConfiguration();
							rpc.Enabled = true;
							rpc.GraphPath = rp.getConfiguration().GraphPath + "_tmc";
							rpc.Profiles = "driving-traffic";
							rpc.UseTrafficInformation = true;
							rpc.ExtStorages = null;
							// Germany only
							rpc.BBox = new Envelope(5.866240, 15.042050, 47.270210, 55.058140);
							
							RoutingProfile rpTmc = new RoutingProfile(rmc.SourceFile, rpc, profiles, loadCntx);

							profiles.add(7777, rpTmc, true);

							m_tmcRouteProfile = rpTmc;

							m_tmcSegments = new TmcSegmentsCollection(loadTmcSegments(m_config.LocationCodesPath, m_tmcRouteProfile, m_tmcRouteProfile.getGraphLocation(), true));

							m_tmcDatasource = TrafficInfoDataSourceFactory.create(m_config.getDataSourceProperties());

							break;
						}
					}
				}
				
				loadCntx.release();

				for (RoutingProfile rp : profiles.getCarProfiles()) {
					if (rp.useTrafficInformation()) {

						RouteProfileTmcData rptd = new RouteProfileTmcData(rp);

						if (updateInfo == null)
						{
							updateInfo = getUpdateInfo();

							saveTmcData(updateInfo);
						}

						updateRouteProfile(rptd, updateInfo, true);

						m_routeProfilesMap.put(rp.hashCode(), rptd);
					}
				}
			}catch(Exception ex)
			{
				logger.warning(ex.getMessage());
				ex.printStackTrace();

				return;
			}
		}

		if (m_config.UpdateInterval > 0) {
			Integer milliSeconds = m_config.UpdateInterval;
			Calendar calendar = Calendar.getInstance();
			calendar.add(Calendar.MILLISECOND, milliSeconds);
			Date firstUpdateTime = calendar.getTime();

			TimerTask timerTask = new UpdateTask(this);
			m_timer = new Timer(true);
			m_timer.schedule(timerTask, firstUpdateTime, milliSeconds);
		}

		m_initialized = true;
	}

	@SuppressWarnings("unchecked")
	private List<TmcSegment> loadTmcSegments(String path, RoutingProfile routeProfile, String outputDir, boolean loadExisting) {
		File segments = Paths.get(path, "SEGMENTS.DAT").toFile();
		File roads = Paths.get(path, "ROADS.DAT").toFile();
		File points = Paths.get(path, "POINTS.DAT").toFile();
		File poffsets = Paths.get(path, "POFFSETS.DAT").toFile();

		Path filePath = Paths.get(outputDir, "location_segments_traffic");
		List<TmcSegment> tmcGraphData = null;
		
		if (m_locationGraph == null)
			m_locationGraph = TrafficLocationGraph.createFromFile(poffsets);

		if (loadExisting) {
			if (filePath.toFile().exists()) {
				try {
					FileInputStream fis = new FileInputStream(filePath.toString());
					ObjectInputStream ois = new ObjectInputStream(fis);
					tmcGraphData = (List<TmcSegment>) ois.readObject();
					ois.close();
					fis.close();
				} catch (IOException ioe) {
					ioe.printStackTrace();
				} catch (ClassNotFoundException c) {
					System.out.println("Class not found");
					c.printStackTrace();
				}
			}
		}

		if (tmcGraphData == null || tmcGraphData.size() == 0) {
			tmcGraphData = TrafficUtility.detectSegments(segments, roads, points, poffsets, routeProfile);

			// serialize TMC segments if needed.
			try {
				FileOutputStream fos = new FileOutputStream(filePath.toString());
				ObjectOutputStream oos = new ObjectOutputStream(fos);
				oos.writeObject(tmcGraphData);
				oos.close();
				fos.close();
			} catch (IOException ioe) {
				ioe.printStackTrace();
			}

			if (DebugUtility.isDebug()) {
				//TrafficUtility.saveTmcSegmentsToShapefile(tmcGraphData, "D:\\Projects\\ORS\\OSM-Files\\tmcedges_53.shp");
			}
		}

		return tmcGraphData;
	}

	public void updateGraphMatching(RoutingProfile rp, String outputDir) {
		if (rp == m_tmcRouteProfile)
		{
			m_tmcSegments = new TmcSegmentsCollection(loadTmcSegments(m_config.LocationCodesPath, m_tmcRouteProfile, outputDir, false));

			updateGraphMatchingInternal(false);
		}
	}

	private void updateRouteProfile(RouteProfileTmcData rptd, TmcUpdateInfo updateInfo, boolean loadExisting) {
		HashMap<Integer, Integer> edgeIdsMap = new HashMap<Integer, Integer>();
		HashMap<Integer, Long> graphOsmIdsMapMatch = m_tmcRouteProfile.getTmcEdges();
		HashMap<Integer, Long> graphOsmIdsMapRoute = rptd.getRouteProfile().getTmcEdges();

		if (graphOsmIdsMapMatch != null && graphOsmIdsMapRoute != null) {
			HashMap<Long, Integer> map = new HashMap<Long, Integer>();

			for (Entry<Integer, Long> entry : graphOsmIdsMapRoute.entrySet()) {
				map.put(entry.getValue(), entry.getKey());
			}

			for (Entry<Integer, Long> entry : graphOsmIdsMapMatch.entrySet()) {
				Integer edgeId = entry.getKey();
				Long osmId = entry.getValue();

				if (map.containsKey(osmId)) {
					edgeIdsMap.put(edgeId, map.get(osmId));
				}
			}

			rptd.setEdgeIdsMap(edgeIdsMap);

			updateEdges(rptd, updateInfo);
		}
	}

	private void updateGraphMatchingInternal(boolean loadExisting) {
		long startTime = System.currentTimeMillis();

		logger.info("TMC: start updating graph matching");

		try {
			TmcUpdateInfo updateInfo = getUpdateInfo();
			for (Entry<Integer, RouteProfileTmcData> entry0 : m_routeProfilesMap.entrySet()) {
				updateRouteProfile(entry0.getValue(), updateInfo, loadExisting);
			}
		} catch (Exception ex) {
			logger.warning(ex.getMessage());
		}

		long seconds = (System.currentTimeMillis() - startTime) / 1000;
		logger.info("TMC: graph matching performed in " + seconds + " s.");
	}

	private void runUpdateEdges() {
		if (m_updateIsRunning)
			return;

		m_updateIsRunning = true;

		try {
			long startTime = System.currentTimeMillis();

			TmcUpdateInfo updateInfo = getUpdateInfo();
			for (Entry<Integer, RouteProfileTmcData> entry : m_routeProfilesMap.entrySet()) {
				updateEdges(entry.getValue(), updateInfo);
			}

			saveTmcData(updateInfo);
			
			long seconds = (System.currentTimeMillis() - startTime) / 1000;
			logger.info("TMC: data is updated. Took " + seconds + " s.");
		} catch (Exception ex) {
			logger.warning(ex.getMessage());
		}

		m_updateIsRunning = false;
	}
	
	private void saveTmcData(TmcUpdateInfo updateInfo) throws Exception
	{
		if (!Helper.isEmpty(m_config.OutputDirectory)) {
			File dir = new File(m_config.OutputDirectory);
			if (dir.isDirectory()) {
				try
				{
			//	Path path = Paths.get(m_config.OutputDirectory, "traffic_data.shp");
				//TrafficUtility.saveMatchedTmcDataToFile(updateInfo.features, path.toString());
				//TmcEventCodesTable.saveToFile(Paths.get(m_config.OutputDirectory, "tmc_codes.txt").toFile());
				}catch (Exception ex)
				{
					logger.warning(ex.getMessage());
				}
			} else {
				Exception ex = new Exception("Unable to write TMC data into " + m_config.OutputDirectory
						+ ". Since the output directory does not exist.");
				logger.warning(ex.getMessage());
			}
		}
	}

	private void updateEdges(RouteProfileTmcData rptd, TmcUpdateInfo updateInfo) {
		HashMap<Integer, Integer> edgeIdsMap = rptd.getEdgeIdsMap();
		if (edgeIdsMap.size() == 0)
			return;

		HashMap<Integer, AvoidEdgeInfo> avoidEdges = new HashMap<Integer, AvoidEdgeInfo>();
		List<Integer> blockedEdges = new ArrayList<Integer>();
		HashMap<Integer, EdgeInfo> edges = new HashMap<Integer, EdgeInfo>();

		try {
			long diff = new Date().getTime() - updateInfo.time.getTime();
			if (true/*DebugUtility.isDebug() /*
															 * more than one
															 * hour
															 */) {
				List<TrafficFeatureInfo> tmcFeatures = updateInfo.features;

				for (TrafficFeatureInfo tfi : tmcFeatures) {
										if (!(tfi.getGeometry() instanceof LineString))
						continue;
						
					short[] codes = new short[tfi.getEventCodes().size()];
					for (int i = 0; i < codes.length; i++) {
						codes[i] = (short)Math.min(Math.max(tfi.getEventCodes().get(i), Short.MIN_VALUE), Short.MAX_VALUE);
					}
					
					String message = tfi.getMessage();
					if (tfi.getEdgeIds() == null)
						continue;
					
					for (int i = 0; i < tfi.getEdgeIds().size(); i++) {
						Integer newEdgeId = edgeIdsMap.get(tfi.getEdgeIds().get(i));
						if ( newEdgeId != null)
							edges.put(newEdgeId, new EdgeInfo(newEdgeId, codes, message));
					}

					for (int i = 0; i < codes.length; i++) {
						int code = codes[i];

						TrafficEventInfo tec = TmcEventCodesTable.getEventInfo(code);
						if (tec != null) {
							int codeType = tec.type;

							for (int edgeId : tfi.getEdgeIds()) {
								if (edgeIdsMap.containsKey(edgeId)) {
									Integer newEdgeId = edgeIdsMap.get(edgeId);
									if (tfi.getEndTime() != null) {
										Date now = new Date();
										if (now.compareTo(tfi.getEndTime()) > 0)
											continue;
									}

									if (codeType == TrafficEventType.AVOID) {
										if (!avoidEdges.containsKey(newEdgeId)){
											AvoidEdgeInfo edgeInfo = new AvoidEdgeInfo(newEdgeId, codes, tec.speedFactor / 2.0f);
											avoidEdges.put(newEdgeId, edgeInfo);
										}
									} else if (codeType == TrafficEventType.BLOCKED) {
										if (!blockedEdges.contains(newEdgeId))
											blockedEdges.add(newEdgeId);
									}
									else if (codeType != TrafficEventType.ANY)
									{
										logger.info("The TMC code '" + codeType +"' is not considered yet.");
									}
								}
							}
						}
					}
				}
			} else {
				logger.info("TMC data is outdated." + updateInfo.time.toString());
			}
		} catch (Exception ex) {
			logger.info(ex.toString());
		}

		// TODO make it thread safe.
		rptd.update(edges, avoidEdges, blockedEdges);
	}

	private TmcUpdateInfo getUpdateInfo() throws ParserConfigurationException, SAXException, IOException,
			ParseException {
		String message = m_tmcDatasource.getMessage();

		if (Helper.isEmpty(message))
			logger.warning("TMC message is null or empty. Check data source configuration. Datasource type: " + m_tmcDatasource.toString());
		
		Date msgTime = TrafficUtility.getMessageDateTime(message);
		List<TrafficFeatureInfo> tmcFeatures = TrafficUtility.extractTmcFeatures(message, m_tmcSegments, 6*60*60*1000, m_locationGraph, logger);

		m_lastUpdateInfo = new TmcUpdateInfo(msgTime, tmcFeatures);
		
		return m_lastUpdateInfo;
		
	}

	public boolean isInitialized() {
		return m_initialized;
	}

	public void destroy() {
		if (m_initialized) {
			if (m_timer != null) {
				m_timer.cancel();
				m_timer = null;
			}
		}

		m_initialized = false;
	}
	
	public String getTimeStamp()
	{
		if (m_lastUpdateInfo != null)
			return dateFormat.format(m_lastUpdateInfo.getTime());
		else
			return "unknown";
	}
	
	public String getTmcInfoAsJson(Envelope env)
	{
        List<TrafficFeatureInfo> tmcFeatures = m_lastUpdateInfo.getFeatures(env);
        
        if (tmcFeatures.size() == 0)
        	return null;

        String result = null;
		
	    try {
	    	StringBuffer buffer = null;
	    	StringWriter sw = new StringWriter();
	    	JSONWriter jw = new JSONWriter(sw);
	    	
	        jw.object();
	        jw.key("type");
	        jw.value("FeatureCollection");
	        
	        jw.key("properties"); 
	        jw.object();
	        jw.key("update_time");
	        jw.value(getTimeStamp());
	        jw.endObject();

	        // Start features
	        jw.key("features");
        	jw.array();
        	
	        for (TrafficFeatureInfo tfi : tmcFeatures)  {
	            // {"geometry": {"type": "MultiLineString", "coordinates": [-94.149, 36.33]}
	        	
	        	//Start feature
	        	jw.object();
	        	jw.key("type");
	        	jw.value("Feature");
	        	
	        	String strCoords = tfi.getGeometryJsonString();
	        	String geomType = null;
	        	
	        	if (Helper.isEmpty(strCoords))
	        	{
	        		strCoords = "";
	        		
	        		if (buffer == null)
	        			buffer = new StringBuffer();
	        		
	        		if (tfi.getGeometry() instanceof LineString)
	        		{
	        			geomType = "LineString";
	        			// "coordinates": [ [100.0, 0.0], [101.0, 1.0] ]

	        			LineString ls = (LineString)tfi.getGeometry();
	        			int nPoints = ls.getNumPoints();
	        			for (int i = 0 ; i < nPoints; i++)
	        			{
	        				Coordinate c = ls.getCoordinateN(i);
	        				strCoords += "["+FormatUtility.formatCoordinate(c, ",", buffer)+"]" + (i < nPoints -1 ? ", ": "");
	        			}
	        		}
	        		else
	        		{
	        			geomType = "Point";

	        			Point p = (Point)tfi.getGeometry();
	        			strCoords += "["+FormatUtility.formatCoordinate(p.getCoordinate(), ",", buffer)+"]";
	        		}

	        		tfi.setGeometryJsonString(strCoords);
	        	}
	        	else
	        	{
	        		if (tfi.getGeometry() instanceof LineString)
  	        		   geomType = "LineString";
	        		else
	        			geomType = "Point";	
	        	}
	            
	        	// start geometry
	        	jw.key("geometry");
	        	jw.object();
	        	
	        	jw.key("type");
	        	jw.value(geomType);
	        	
	        	jw.key("coordinates");
	        	jw.array();
	        	//jw.value(strCoords);
	        	sw.write(strCoords); // this is a hack, since jw.value always adds double quotes
	        	jw.endArray();
	        	// end geometry
	        	jw.endObject();
	        	
	        	// start properties
	        	jw.key("properties"); 
	        	jw.object();
	        	jw.key("codes");
	        	jw.value(tfi.getEventCodesAsString());
	        	
	        	jw.key("message");
	        	jw.value(tfi.getMessage());
	        	
	        	jw.key("distance");
	        	jw.value(FormatUtility.formatDistance(tfi.getDistance(), "M"));
	        	
	        	if (tfi.getStartTime() != null)
	        	{
	        		jw.key("start_time");
		        	jw.value(dateFormat.format(tfi.getStartTime()));
	        	}
	        	
	        	if (tfi.getEndTime() != null)
	        	{
	        		jw.key("end_time");
		        	jw.value(dateFormat.format(tfi.getEndTime()));
	        	}
		            
	        	// end properties
	        	jw.endObject();
	        	
	        	// end feature
	        	jw.endObject();
	        }
	        
	        // End features
	     	jw.endArray();

	     	// End 
	        jw.endObject();

	        sw.flush();
	        result = sw.toString();
	        
	        sw.close();
	    } catch (JSONException e) {
	    	logger.warning("Can't save json object: " + e.toString());
	    }
	    catch (IOException e) {
	    	logger.warning("Can't save json object: " + e.toString());
	    }

	    return result;	
	}

	public String getEdgeMessage(GraphStorage graphStorage, int edgeId) {
		RouteProfileTmcData rptd = getRouteProfileTmcData(graphStorage);

		if (rptd == null)
			return "";
		else {
			EdgeInfo ei = rptd.getEdges().get(edgeId);

			if (ei != null)
				return ei.getCodesAsString() + " | " + ei.getMessage();
			else
				return null;
		}
	}

	public List<Integer> getBlockedEdges(GraphStorage graphStorage) {
		RouteProfileTmcData rptd = getRouteProfileTmcData(graphStorage);

		if (rptd == null)
			return null;
		else
			return rptd.getBlockedEdges();
		 //Test AAS 
		 /*{
			List<Integer> list = new ArrayList();
			for(Entry<Integer, AvoidEdgeInfo> entry: rptd.getAvoidEdges().entrySet())
			{
				list.add(entry.getValue().getEdgeId());
			}
			
			return list;
		}*/
	}

	public HashMap<Integer, AvoidEdgeInfo> getAvoidEdges(GraphStorage graphStorage) {
		RouteProfileTmcData rptd = getRouteProfileTmcData(graphStorage);

		if (rptd == null)
			return null;
		else
			return rptd.getAvoidEdges();
	}
	
	private RouteProfileTmcData getRouteProfileTmcData(GraphStorage graphStorage)
	{
		return m_routeProfilesMap.get(graphStorage.getDirectory().getLocation().hashCode());
	}
}
