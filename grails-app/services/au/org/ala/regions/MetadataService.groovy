package au.org.ala.regions

import grails.converters.JSON
import org.codehaus.groovy.grails.commons.ConfigurationHolder

class MetadataService {

    static transactional = true

    /**
     * Cache for metadata about 'objects' in 'fields' (eg individual states within the 'states' field).
     * This is populated by spatial services calls.
     */
    static regionCache = [:]
    static regionCacheLastRefreshed = [:]

    /**
     * Cache for metadata about region types (eg fid, layer name).
     * This is populated from external 'static' config.
     */
    static regionsMetadataCache = null

    /**
     * Cache for metadata about region objects that are layers rather than objects - the 'other' regions
     * This is populated from external 'static' config AND then enriched by spatial services calls.
     */
    static otherRegions = null
    //static otherRegionsCacheLastRefreshed = [:]

    static logReasonCache =loadLoggerReasons()

    /* cache management */
    def clearCaches = {
        regionsMetadataCache = null
        regionCache = [:]
        otherRegions = null
        logReasonCache=loadLoggerReasons()
    }

    static def loadLoggerReasons(){
        println("Refreshing the download reasons")
        String url = "http://logger.ala.org.au/service/logger/reasons"
        def conn = new URL(url).openConnection()
        def map = [:]
        try{
            conn.setConnectTimeout(10000)
            conn.setReadTimeout(50000)
            def json = conn.content.text
            def result = JSON.parse(json)
            println("JSON :: " + json)
            println(result)
            result.each{
                map[it.id] = it.name
            }
            println("log reason map::" + map)
        } catch (Exception e){
            //load the default
            println("Using the default list...")
            return defaultRegionsMetadata
        }
        return map
    }



    /**
     * Get some metadata for a region.
     *
     * If name is defined then just return metadata for that named region
     * else for all regions of the specified type.
     *
     * @param type type of region
     * @param name optional name of the region (the 'object')
     * @return name, area, pid and bbox as a map for the named region or a map of all objects if no name supplied
     */
    def regionMetadata(type, name) {
        if (type == 'other') {
            return getOtherRegions()
        }
        def fid = fidFor(type)
        //println "MS: ${type} - ${fid}"
        def regionMd = getRegionMetadata(fid)
        if (name) {
            // if a specific region is named, then return that
            return regionMd[name]
        }
        else if (regionMd.values().size == 1){
            // if there is only one object in the field, return it
            return regionMd.values().iterator().next()
        }
        else {
            // return all objects in the field
            return regionMd
        }
    }

    /**
     * Return metadata for the specified field.
     * Use cached metadata if available and fresh.
     *
     * @param fid
     * @return
     */
    def getRegionMetadata(fid) {
        //println "checking cache for ${fid}: cache date is ${regionCacheLastRefreshed[fid]}; current date is ${new Date()}"
        if (!regionCache[fid] || new Date().after(regionCacheLastRefreshed[fid] + 2)) {
            regionCache[fid] = new TreeMap() // clear any stale content
            println "clearing cache for ${fid}"
            regionCacheLastRefreshed[fid] = new Date()
            //println "new cache date is ${regionCacheLastRefreshed[fid]}"
            def url = ConfigurationHolder.config.spatial.baseURL + 'ws/field/' + fid
            def conn = new URL(url).openConnection()
            try {
                conn.setConnectTimeout(10000)
                conn.setReadTimeout(50000)
                def json = conn.content.text
                def result = JSON.parse(json)
                result.objects.each {
                    // TODO: write type-specific closures to do this filtering
                    if (!(it.name in ['Unknown1', 'Macquarie Island', 'Ashmore and Cartier Islands', 'Coral Sea Islands'])) {
                        regionCache[fid].put it.name,
                                [name: it.name, pid: it.pid, bbox: parseBbox(it.bbox), area_km: it.area_km]
                    }
                }
            } catch (SocketTimeoutException e) {
                def message = "Timed out looking up pid. URL= ${url}."
                log.warn message
                println message
                return [error: true, errorMessage: message]
            } catch (Exception e) {
                def message = "Failed to lookup pid. ${e.getClass()} ${e.getMessage()} URL= ${url}."
                log.warn message
                println message
                return [error: true, errorMessage: message]
            }
        }
        return regionCache[fid]
    }

    /**
     * Retrieve the specified property value for the specified layer.
     *
     * Will cause a service lookup if the property is not in the cache.
     *
     * @param name the human readable name of the layer (displayname)
     * @param property to be retrieved
     * @return the value of the specified property
     */
    def getLayerMetadata(name, property) {
        if (!getOtherRegions()[name]) {

            // we don't know about it
            // Check if it's a subregion
            def result = lookupLayerMetadata(lookupLayerName(name))

            if (result && result[property]) {
                return result[property]
            }

            if (property == 'fid') {
                return fidForLayer(name)
            }
            else if (property == 'bbox') {
                return [minLat: -42, minLng: 113, maxLat: -14, maxLng: 153]
            }
            else {
                return null
            }
        }
        // check cache first
        def value = getOtherRegions()[name][property]
        //println "value of ${property} for ${name} is ${value}"
        if (!value) {
            // else lookup and load all properties
            getOtherRegions()[name] += lookupLayerMetadata(getOtherRegions()[name].layerName)
        }
        return getOtherRegions()[name][property]
    }

    /**
     * Uses the 'layers/more/<layerName>' service to retrieve metadata for a layer.
     *
     * @param layerName the name of the layer in geoserver
     * @return a map of available properties
     */
    def lookupLayerMetadata(layerName) {
        println "getting metadata for " + layerName
        def url = ConfigurationHolder.config.spatial.baseURL + "layers/more/" +
                layerName.encodeAsURL() + ".json"

        def conn = new URL(url).openConnection()
        try {
            conn.setConnectTimeout(10000)
            conn.setReadTimeout(50000)
            def json = conn.content.text
            def more = JSON.parse(json).layer
            return [id: more.id, layerName: more.name, source: more.sourcelink, notes: more.notes,fid: "cl"+more.id,
                    bbox: [minLat: more.minlatitude, minLng: more.minlongitude,
                            maxLat: more.maxlatitude, maxLng: more.maxlongitude] ]
        } catch (SocketTimeoutException e) {
            log.warn "Timed out looking up layer details. URL= ${url}."
            [error: "Timed out looking up layer details."]
        } catch (Exception e) {
            log.warn "Failed to lookup layer details. ${e.getClass()} ${e.getMessage()} URL= ${url}."
            [error: "Failed to lookup layer details. ${e.getClass()} ${e.getMessage()} URL= ${url}."]
        }
    }

    /**
     * Get the fid (field id) of the layer that represents the specified region type.
     * @param regionType
     * @return
     */
    def fidFor(regionType) {
        switch (regionType) {
            case 'other': return 'other'
            default: return getRegionsMetadata()[regionType].fid
        }
    }

    /**
     * Get the bounding box for the specified region if known
     * else a sensible default.
     *
     * @param regionType
     * @param regionName
     * @return
     */
    Map lookupBoundingBox(regionType, regionName) {
        //println "MS: regionType=${regionType} regionName=${regionName}"
        if (regionType == 'layer') {
            return getLayerMetadata(regionName, 'bbox') as Map
        }
        def bbox = regionMetadata(regionType, regionName)?.bbox
        return bbox ?: [minLat: -42, minLng: 113, maxLat: -14, maxLng: 153]
    }

    /**
     * Returns list of types of regions.
     * @return
     */
    def getRegionTypes() {
        return getRegionsMetadata().collect {k,v -> v.name}
    }

    /**
     * Return metadata for region types.
     *
     * Uses cache if available
     * else external config
     * else the default set.
     * @return
     */
    def getRegionsMetadata() {
        // use cache if loaded
        if (regionsMetadataCache) {
            return regionsMetadataCache
        }
        // use external file if available
        def md = new File("/data/regions/config/regions-metadata.json")?.text
        if (md) {
            regionsMetadataCache = JSON.parse(md)
            return regionsMetadataCache
        }
        // last resort - use static metadata
        else {
            return defaultRegionsMetadata
        }
    }

    /**
     * Returns the metadata for region types as json.
     * @return
     */
    def getRegionsMetadataAsJson() {
        return getRegionsMetadata() as JSON
    }

    /**
     * Returns the metadata for regions as a javascript object literal.
     * @return
     */
    def getRegionsMetadataAsJavascript() {
        return "var REGIONS = {metadata: " + getRegionsMetadataAsJson() + "}"
    }

    /**
     * Use spatial services calls to inject additional metadata to the 'other' regions metadata.
     * @return
     */
    def populateOtherRegions() {
        otherRegions.each { key, obj ->
            //println "MS: ${key} " + lookupLayerMetadata(obj.layerName).bbox
            otherRegions[key] += lookupLayerMetadata(obj.layerName)
        }
//        otherRegionCacheLastRefreshed['other'] = new Date()
    }

    /**
     * Returns fully-populated metadata for 'other' regions.
     *
     * @return
     */
    def getOtherRegions() {
        // seed list of other regions if needed
        if (!otherRegions) {
            // use external file if available
            def md = new File("/data/regions/config/layer-regions-metadata.json")?.text
            if (md) {
                otherRegions = JSON.parse(md)
            }
            // last resort - use static metadata
            else {
                otherRegions = defaultOtherRegions.clone()
            }
            populateOtherRegions()
        }

        return otherRegions;
    }

    def getStateEmblems() {
        def json = JSON.parse(new FileInputStream("/data/regions/config/state-emblems.json"), "UTF-8")
        return json
    }

    /**
     * Get a list of the objects in a layer and the available metadata.
     *
     * @param fid id of the 'field' (type of region)
     * @return name, area, pid and bbox as a map for all objects
     */
   def getObjectsForALayer(fid) {
        def results = [:]
        def url = ConfigurationHolder.config.spatial.baseURL + 'layers-service/field/' + fid
        def conn = new URL(url).openConnection()
        try {
            conn.setConnectTimeout(10000)
            conn.setReadTimeout(50000)
            def json = conn.content.text
            def result = JSON.parse(json)
            result.objects.each {
                results.put it.name,
                        [name: it.name, pid: it.pid, bbox: parseBbox(it.bbox), area_km: it.area_km]
            }
        } catch (SocketTimeoutException e) {
            log.warn "Timed out looking up fid. URL= ${url}."
            println "Timed out looking up fid. URL= ${url}."
        } catch (Exception e) {
            log.warn "Failed to lookup fid. ${e.getClass()} ${e.getMessage()} URL= ${url}."
            println "Failed to lookup fid. ${e.getClass()} ${e.getMessage()} URL= ${url}."
        }
        return results
    }

    /**
     * Converts a bounding box from a polygon format to min/max*lat/lng format.
     *
     * @param bbox as polygon eg bbox: POLYGON((158.684997558594 -55.116943359375,158.684997558594 -54.4854164123535,
     * 158.950012207031 -54.4854164123535,158.950012207031 -55.116943359375,158.684997558594 -55.116943359375))
     * @return
     */
    def parseBbox(String bbox) {
        //println "bbox = ${bbox}"
        if (!bbox) return [:]

        def coords = bbox[9..-3]
        def corners = coords.tokenize(',')
        def sw = corners[0].tokenize(' ')
        def ne = corners[2].tokenize(' ')
        return [minLat: sw[1], minLng: sw[0], maxLat: ne[1], maxLng: ne[0]]
    }

    /**
     * Returns the PID for a named region.
     * @param regionType
     * @param regionName
     * @return
     */
    def lookupPid(regionType, regionName) {
        if (regionType == 'layer') {
            return ""
        }
        return regionMetadata(regionType, regionName)?.pid
    }

    /**
     * Returns the layerName for the specified region type.
     * @param regionType
     * @return
     */
    String layerNameForType(regionType) {
        return getRegionsMetadata()[regionType]?.layerName
    }

    /**
     * Backup regions metadata - for when all other sources are unavailable
     */
    static defaultRegionsMetadata = [
            states: [name: 'states', layerName: 'aus1', fid: 'cl22', bieContext: 'aus_states', order: 0],
            lgas: [name: 'lgas', layerName: 'lga_aust', fid: 'cl959', bieContext: 'gadm_admin', order: 1],
            ibras: [name: 'ibras', layerName: 'ibra_merged', fid: 'cl20', bieContext: 'ibra_no_states', order: 2],
            imcras: [name: 'imcras', layerName: 'imcra4_pb', fid: 'cl21', bieContext: 'imcra', order: 3],
            nrms: [name: 'nrms', layerName: 'nrm_regions_2010', fid: 'cl916', bieContext: 'nrm', order: 4],
            other: [name: 'other', layerName: '', fid: 'other', bieContext: '', order: 5]
    ]

    static defaultLoggerReasons =[
            0: "conservation management/planning",
            1: "biosecurity management",
            2: "environmental impact, site assessment",
            3: "education",
            4: "scientific research",
            5: "collection management",
            6: "other",
            7: "ecological research",
            8: "systematic research",
            9: "other scientific research",
            10: "testing"
    ]

    /**
     * Backup other regions metadata - for when all other sources are unavailable
     */
    static defaultOtherRegions = [
            'Great Eastern Ranges Initiative': [
                    name: 'Great Eastern Ranges Initiative',
                    layerName: 'ger_initiative'
            ],
            'RAMSAR wetland regions': [
                    name: 'RAMSAR wetland regions',
                    layerName: 'ramsar'
            ],
            'Directory of Important Wetlands': [
                    name: 'Directory of Important Wetlands',
                    layerName: 'diwa_type_criteria'
            ],
            'Areas for Further Assessment within the East Marine Region': [
                    name: 'Areas for Further Assessment within the East Marine Region',
                    layerName: 'east_afa_final'
            ],
            'Australian Coral Ecoregions': [
                    name: 'Australian Coral Ecoregions',
                    layerName: 'australian_coral_ecoregions'
            ],
            'Collaborative Australian Protected Areas Database': [
                    name: 'Collaborative Australian Protected Areas Database',
                    layerName: 'capad08_ext'
            ],
            'Exclusive Economic Zone': [
                    name: 'Exclusive Economic Zone',
                    layerName: 'eez_poly'
            ],
            'Myrtle Rust Observations': [
                    name: 'Myrtle Rust Observations',
                    layerName: 'myrtle_rust'
            ]
    ]

    /*********************************************************************************************************
     * The following methods supply metadata for 'sub-regions'. These are GER areas that are not directly
     * accessible from the regions front page. They are currently included to show the potential for region
     * exploration.
     *
     * We need a plan for dealing with these 'sub-areas' before we can externalise the config properly.
     ********************************************************************************************************/

    /**
     * Returns the layer name for a specified 'other' region.
     *
     * @param name
     * @return
     */
    String lookupLayerName(name) {
        switch (name.toLowerCase()) {
            case "great eastern ranges initiative": return "ger_initiative"
            case "ramsar wetland regions": return "ramsar"
            case "hunter valley partnership": return "ger_hunter_valley_20121031"
            case "slopes to summit": return "ger_slopes_to_summit_20121031"
            case "kosciuszko to coast": return "ger_kosciuszko2coast_20121031"
            case "border ranges alliance": return "ger_border_ranges_20121031"
            case "myrtle rust observations": return "myrtle_rust";
            case "kanangra-boyd to wyangala link": return "ger_kanangra_wyangala";
            case "jaliigirr biodiversity alliance": return "ger_jaliigirr_20121031";
            case "illawarra to shoalhaven": return "ger_illawarra_shoalhaven_20121031";
            case "southern highlands link": return "ger_southern_highlands_20121031";
            case "hinterland bush links": return ""
            case "central victorian biolinks": return "ger_cvb2"
            default: return regionMetadata('other',null)[name]?.layerName
        }
    }

    /**
     * Defines a hierarchy for GER sub-regions.
     *
     * @param name
     * @return
     */
    Map lookupParentChain(name) {
        switch (name.toLowerCase()) {
            case "hunter valley partnership": return [type:'layer',name:'Great Eastern Ranges Initiative']
            case "slopes to summit": return [type:'layer',name:'Great Eastern Ranges Initiative']
            case "kosciuszko to coast": return [type:'layer',name:'Great Eastern Ranges Initiative']
            case "border ranges alliance": return [type:'layer',name:'Great Eastern Ranges Initiative']
            case "kanangra-boyd to wyangala link": return [type:'layer',name:'Great Eastern Ranges Initiative']
            case "jaliigirr biodiversity alliance": return [type:'layer',name:'Great Eastern Ranges Initiative']
            case "illawarra to shoalhaven": return [type:'layer',name:'Great Eastern Ranges Initiative']
            case "southern highlands link": return [type:'layer',name:'Great Eastern Ranges Initiative']
            case "hinterland bush links": return [type:'layer',name:'Great Eastern Ranges Initiative']
            case "central victorian biolinks": return [type:'layer',name:'Great Eastern Ranges Initiative']
            default: return [:]
        }
    }

    /**
     * Returns a description for GER sub-regions to simulate richer region metadata.
     * @param name
     * @return
     */
    String lookupDescription(name) {
        switch (name.toLowerCase()) {
            case "great eastern ranges initiative": return 'The Great Eastern Ranges Initiative (GER) is bringing people and organisations together to protect, link and restore healthy habitats over 3,600 kilometers from Western Victoria, through NSW and the ACT, to Far North Queensland. GER is a strategic response to mitigate the potential impacts of climate change, invasive species, land clearing and other environmental changes on the Great Eastern Ranges. This vast area contains Australia’s richest diversity of plants and animals and catchments that provide a reliable, clean source of water for over 90% of eastern Australia’s population. Visit <a href="http://www.greateasternranges.org.au">www.greateasternranges.org.au</a> for more information.'
            case "hunter valley partnership": return 'The Hunter Valley comprises a complex region of east-west and north-south connections, in one of the few parts of the GER corridor where the Great Dividing Range diminishes to a naturally low and narrow line of hills. The landscape supports of complex mix of inland and coastal species, and forms a natural ‘bottle-neck’ for forest and woodland species migrating along the ranges. <a href="http://www.greateasternranges.org.au/our-partners/ger-regional-partnerships/hunter-valley-partnership/">Click here for more information.</a>'
            case "slopes to summit": return 'The Slopes to Summit landscape area forms a natural altitudinal gradient linking the high country habitats of the Australian Alps and northern central Victoria, with the temperate woodlands and grasslands of inland NSW. <a href="http://www.greateasternranges.org.au/our-partners/ger-regional-partnerships/slopes-to-summit/">Click here for more information.</a>'
            case "kosciuszko to coast": return 'The Kosciuszko to Coast region links the Australian Alps National Parks through natural temperate grasslands and woodlands to tablelands, forests and coastal ecosystems. This creates the tallest altitudinal gradient in the GER corridor between Australia’s highest peak Mount Kosciuszko (2,228 meters) and sea level. <a href="http://www.greateasternranges.org.au/our-partners/ger-regional-partnerships/kosciusko2coast/">Click here for more information.</a>'
            case "border ranges alliance": return "The Border Ranges is one of Australia's most biologically diverse regions, a spectacular backdrop to local communities, and home to many unique plants and animals. It is part of the World Heritage listed Gondwana Rainforests of Australia. <a href=\"http://www.greateasternranges.org.au/our-partners/ger-regional-partnerships/border-ranges-alliance/\">Click here for more information.</a>"
            case "southern highlands link": return 'The Southern Highlands Link provides several connections linking the World Heritage-listed reserves of the Greater Blue Mountains with major sandstone reserves to the south. Although parts of the Southern Highlands have been extensively cleared for agriculture and urban expansion, it remains an important landscape for a variety of bird migrations along the ranges and between the coast and inland. <a href="http://www.greateasternranges.org.au/our-partners/ger-regional-partnerships/southern-highlands-link/">Click here for more information.</a>'
            case "kanangra-boyd to wyangala link": return 'The Kanangra-Boyd to Wyangala Link forms a natural corridor connecting the cool forests of Australia’s east coast with the drier temperate and semi-arid woodlands of the interior. The corridor allows species which are normally associated with forest environments to occupy habitat which is not available in the surrounding landscape, and also provides a refuge for woodland species during periods of prolonged drought. <a href="http://www.greateasternranges.org.au/our-partners/ger-regional-partnerships/kanangra-boyd-to-wyangala-link/">Click here for more information.</a>'
            case "jaliigirr biodiversity alliance": return 'The Jaliigirr (Coffs Coast hinterland) landscape is located at the convergence of tropical, subtropical and temperate zones creating a unique diversity and complexity of habitats and species. These ecological communities provide our water supply, clean air, crop pollination, nutrient recycling, food, medicines, building materials and the regeneration of primary production soils, contributing billions of dollars to our local economy annually. <a href="http://www.greateasternranges.org.au/our-partners/ger-regional-partnerships/jalliigirr-biodiversity-alliance/">Click here for more information.</a>'
            case "illawarra to shoalhaven": return 'The Illawarra to Shoalhaven landscape comprises both a major north-south rainforest corridor formed by the Illawarra and Cambewarra escarpments, and a series of altitudinal linkages enabling seasonal migration of species between coastal reserves, the Southern Highlands and beyond. <a href="http://www.greateasternranges.org.au/our-partners/ger-regional-partnerships/illawarra-to-shoalhaven/">Click here for more information.</a>'
            case "hinterland bush links": return 'Centred on the Glasshouse Mountains, the Sunshine Coast Hinterland Bush Links connects habitats comprising a natural biodiversity hotspot between Caboolture and Gympie, and from the coast to Nanango. Populations of many species are declining in the area due to loss, degradation and fragmentation of habitat, making the landscape important for locally resident species as well as migratory fauna. <a href="http://www.greateasternranges.org.au/our-partners/ger-regional-partnerships/hinterland-bush-links/">Click here for more information</a>'
            case "central victorian biolinks": return 'The Central Victorian BioLinks landscape comprises a complex mosaic of local and continental scale landscape linkages connecting the Grampians with the Victorian Alps, and supporting seasonal species movements between the Murray River and the Macedon Ranges. The landscape occurs on a naturally low section of the eastern ranges, making the woodlands and riparian forests of the landscape even more important for nomadic and migratory species. <a href="http://www.greateasternranges.org.au/our-partners/ger-regional-partnerships/central-victorian-biolinks/">Click here for more information.</a>'
            default: return ""
        }
    }

    /**
     * Returns the fid (field identifier) for GER sub-regions.
     *
     * @param region
     */
    private fidForLayer(region) {
        switch (region.toLowerCase()) {
            case "great eastern ranges initiative": return "cl2049" //replaces cl904;
            case "ramsar wetland regions": return "cl935";
            case "hunter valley partnership": return "cl1063"; //replaces cl905
            case "border ranges alliance": return "cl1062"; //replaces cl903
            case "kosciuszko to coast": return "cl1067"; //replaces cl909
            case "slopes to summit": return "cl1069"; //replaces cl912
            case "Myrtle Rust Observations": return "cl934";
            case "southern highlands link": return 'cl1070';
            case "kanangra-boyd to wyangala link": return 'cl2050';
            case "jaliigirr biodiversity alliance": return 'cl1065';
            case "illawarra to shoalhaven": return 'cl1064';
            case "hinterland bush links": return ''
            case "central victorian biolinks": return 'cl2048'
            default: return regionMetadata('other',null)[region]?.fid
        }
    }

}
