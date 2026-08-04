metadata {
    definition(
        name: "Solcast_dual",
        namespace: "alan_f",
        author: "Alan F",
        importUrl: "https://raw.githubusercontent.com/youzer-name/Solcast_dual/main/solcast.groovy",
    ) {
        capability "Refresh"
        capability "EnergyMeter"
        capability "PowerMeter"

        attribute "energy", "number"
        attribute "power", "number"
        attribute "24_Hour_Peak_Production_a", "number"
        attribute "24_Hour_Peak_Production_b", "number"
        attribute "48_Hour_Peak_Production_a", "number"
        attribute "48_Hour_Peak_Production_b", "number"
        attribute "72_Hour_Peak_Production_a", "number"
        attribute "72_Hour_Peak_Production_b", "number"
        attribute "1_Hour_Estimate_a", "number"
        attribute "1_Hour_Estimate_b", "number"
        attribute "1_Hour_Estimate", "number"
        attribute "one_hour_estimate", "number"
        attribute "24_Hour_Estimate", "number"
        attribute "24_Hour_Estimate_High", "number"
        attribute "24_Hour_Estimate_Low", "number"
        attribute "48_Hour_Estimate", "number"
        attribute "48_Hour_Estimate_High", "number"
        attribute "48_Hour_Estimate_Low", "number"
        attribute "72_Hour_Estimate", "number"
        attribute "72_Hour_Estimate_High", "number"
        attribute "72_Hour_Estimate_Low", "number"
        attribute "lastUpdate", "string"
        attribute "html24hour", "string"
    }
    preferences {
        input name: "logEnable", type: "bool", title: "Enable Info logging", defaultValue: true, description: ""
        input name: "debugLog", type: "bool", title: "Enable Debug logging", defaultValue: false, description: ""
        input name: "htmlTile", type: "bool", title: "Create Dashboard Tile?", defaultValue: false, description: ""
        input name: "api_key", type: "string", title: "API Key", required: true
        input name: "resource_id_a", type: "string", title: "Site Resource ID_a", required: true
        input name: "resource_id_b", type: "string", title: "Site Resource ID_b", required: true
        input("refresh_interval", "enum", title: "How often to refresh the forecast data", options: [
            0: "Do NOT update",
            30: "30 minutes",
            1: "1 Hour",
            3: "3 Hours",
            8: "8 Hours",
            12: "12 Hours",
            24: "Daily",
        ], required: true, defaultValue: "0")
        input name: "refreshhour", type: "enum", title: "Refresh time (hour)", defaultValue: 0, description: "Only applies to Daily interval", options:["0","1","2","3","4","5","6","7","8","9","10","11","12","13","14","15","16","17","18","19","20","21","22","23"]
        input name: "refreshminute", type: "enum", title: "Refresh time (minute)", defaultValue: 0, options:["1","2","3","4","5","6","7","8","9","10","11","12","13","14","15","16","17","18","19","20","21","22","23","24","25","26","27","28","29","30","31","32","33","34","35","36","37","38","39","40","41","42","43","44","45","46","47","48","49","50","51","52","53","54","55","56","57","58","59"]
        input name: "randomize", type: "bool", title: "Randomize seconds?", defaultValue: true, description: "Perform refresh random seconds after selected hour:minute"

    }
}

def version() {
    return "1.0.4"
    //1.0.2 - add settable refresh time and random seconds option
    //1.0.3 - fix typo in next1_b calculation, add delay between API calls
    //1.0.4 - fix settings.refreshminute reference in schedule calls, fix lastUpdate sendEvent, remove spaces from attribute names
}

def installed() {
    if (logEnable) log.info "Driver installed"

    state.version = version()
}

def uninstalled() {
    unschedule(refresh)
    if (logEnable) log.info "Driver uninstalled"
}

def updated() {
    if (logEnable) log.info "Settings updated"
    if (settings.refresh_interval != "0") {
        //refresh()
        def refreshseconds = 0
    if (settings.randomize)
        {
        refreshseconds = Math.abs(new Random().nextInt() % 59) +1
        }

        if (settings.refresh_interval == "24") {
           schedule("${refreshseconds} ${settings.refreshminute} ${settings.refreshhour} ? * * *", refresh, [overwrite: true])
        } else if(settings.refresh_interval == "30"){
        schedule("${refreshseconds} */30 * ? * *", refresh, [overwrite: true])
        } else {
        schedule("${refreshseconds} ${settings.refreshminute} */${settings.refresh_interval} ? * * *", refresh, [overwrite: true])
        }
    }else{
        unschedule(refresh)
    }
    state.version = version()
}

import groovy.json.JsonOutput;
def refresh() {

    def outputTZ = TimeZone.getTimeZone('UTC')

    def next1 = 0;
    def next24 = 0;
    def next24High = 0;
    def next24Low = 0;
    def next48 = 0;
    def next48High = 0;
    def next48Low = 0;
    def next72 = 0;
    def next72High = 0;
    def next72Low = 0;

    def host = "https://api.solcast.com.au/rooftop_sites/${resource_id_a}/forecasts?format=json&api_key=${api_key}&hours=72"
    if(debugLog) log.debug host
    def forecasts = httpGet([uri: host]) {resp -> def respData = resp.data.forecasts}
    //if(debugLog) log.debug JsonOutput.toJson(forecasts)
    def next1_a = 0;
    def next24_a = 0;
    def next24High_a = 0;
    def next24Low_a = 0;
    def next48_a = 0;
    def next48High_a = 0;
    def next48Low_a = 0;
    def next72_a = 0;
    def next72High_a = 0;
    def next72Low_a = 0;
    def size = forecasts.size();
    for(int x=0; x<size; x++){
        if(debugLog) log.debug x + " : " + forecasts[x]
        def pv_estimate = forecasts[x].pv_estimate/2
        def pv_estimate_high = forecasts[x].pv_estimate90/2
        def pv_estimate_low = forecasts[x].pv_estimate10/2
        if(x < 2){
            next1_a = next1_a + pv_estimate
        }
        if(x < 48){
            next24_a = next24_a + pv_estimate
            next24High_a = next24High_a + pv_estimate_high
            next24Low_a = next24Low_a + pv_estimate_low
        }
        if(x < 96){
            next48_a = next48_a + pv_estimate
            next48High_a = next48High_a + pv_estimate_high
            next48Low_a = next48Low_a + pv_estimate_low
        }
        next72_a = next72_a + pv_estimate
        next72High_a = next72High_a + pv_estimate_high
        next72Low_a = next72Low_a + pv_estimate_low
    }

    def tomorrow = new Date().next().format("yyyy-MM-dd'T'HH:mm:ss'Z'",outputTZ)
    def forecast24_a = forecasts.findAll { it.period_end < tomorrow}
    //if(debugLog) log.debug forecast24_a
    def peak24_a = forecast24_a.max() { it.pv_estimate }

    def twoDays = new Date().plus(2).format("yyyy-MM-dd'T'HH:mm:ss'Z'",outputTZ)
    def forecast48_a = forecasts.findAll { it.period_end < twoDays}
    def peak48_a = forecast48_a.max() { it.pv_estimate }

    def peak72_a = forecasts.max() { it.pv_estimate }

    if(logEnable) log.info  "{ \"next1_a\": " + next1_a + ", \"next24_a\": " +  next24_a + ", \"next24High_a\": " +  next24High_a + ", \"next24Low_a\": " + next24Low_a  + ", \"next48_a\": " + next48_a + ", \"next48High_a\": " + next48High_a + ", \"next48Low_a\": " + next48Low_a + ", \"next72_a\": " + next72_a + ", \"next72High_a\": " + next72High_a + ", \"next72Low_a\": " +  next72Low_a + "}";


//delay between API calls

    pauseExecution(30000)

// API call for b site
    host = "https://api.solcast.com.au/rooftop_sites/${resource_id_b}/forecasts?format=json&api_key=${api_key}&hours=72"
    if(debugLog) log.debug host
    forecasts = httpGet([uri: host]) {resp -> def respData = resp.data.forecasts}
    //if(debugLog) log.debug JsonOutput.toJson(forecasts)
    def next1_b = 0;
    def next24_b = 0;
    def next24High_b = 0;
    def next24Low_b = 0;
    def next48_b = 0;
    def next48High_b = 0;
    def next48Low_b = 0;
    def next72_b = 0;
    def next72High_b = 0;
    def next72Low_b = 0;

    size = forecasts.size();
    for(int x=0; x<size; x++){
        if(debugLog) log.debug x + " : " + forecasts[x]
        def pv_estimate = forecasts[x].pv_estimate/2
        def pv_estimate_high = forecasts[x].pv_estimate90/2
        def pv_estimate_low = forecasts[x].pv_estimate10/2
        if(x < 2){
            next1_b = next1_b + pv_estimate
        }
        if(x < 48){
            next24_b = next24_b + pv_estimate
            next24High_b = next24High_b + pv_estimate_high
            next24Low_b = next24Low_b + pv_estimate_low
        }
        if(x < 96){
            next48_b = next48_b + pv_estimate
            next48High_b = next48High_b + pv_estimate_high
            next48Low_b = next48Low_b + pv_estimate_low
        }
        next72_b = next72_b + pv_estimate
        next72High_b = next72High_b + pv_estimate_high
        next72Low_b = next72Low_b + pv_estimate_low
    }

    tomorrow = new Date().next().format("yyyy-MM-dd'T'HH:mm:ss'Z'",outputTZ)
    def forecast24_b = forecasts.findAll { it.period_end < tomorrow}
    //if(debugLog) log.debug forecast24_b
    def peak24_b = forecast24_b.max() { it.pv_estimate }

    twoDays = new Date().plus(2).format("yyyy-MM-dd'T'HH:mm:ss'Z'",outputTZ)
    def forecast48_b = forecasts.findAll { it.period_end < twoDays}
    def peak48_b = forecast48_b.max() { it.pv_estimate }

    def peak72_b = forecasts.max() { it.pv_estimate }

    if(logEnable) log.info  "{ \"next1_b\": " + next1_b + ", \"next24_b\": " +  next24_b + ", \"next24High_b\": " +  next24High_b + ", \"next24Low_b\": " + next24Low_b  + ", \"next48_b\": " + next48_b + ", \"next48High_b\": " + next48High_b + ", \"next48Low_b\": " + next48Low_b + ", \"next72_b\": " + next72_b + ", \"next72High_b\": " + next72High_b + ", \"next72Low_b\": " +  next72Low_b + "}";


//calculate totals

    def est_1hour = (next1_a + next1_b).setScale(2, BigDecimal.ROUND_HALF_UP)
    def est_24hour = (next24_a + next24_b).setScale(2, BigDecimal.ROUND_HALF_UP)
    def est_48hour = (next48_a + next48_b).setScale(2, BigDecimal.ROUND_HALF_UP)
    def est_72hour = (next72_a + next72_b).setScale(2, BigDecimal.ROUND_HALF_UP)

    def est_24hour_high = (next24High_a + next24High_b).setScale(2, BigDecimal.ROUND_HALF_UP)
    def est_48hour_high = (next48High_a + next48High_b).setScale(2, BigDecimal.ROUND_HALF_UP)
    def est_72hour_high = (next72High_a + next72High_b).setScale(2, BigDecimal.ROUND_HALF_UP)

    def est_24hour_low = (next24Low_a + next24Low_b).setScale(2, BigDecimal.ROUND_HALF_UP)
    def est_48hour_low = (next48Low_a + next48Low_b).setScale(2, BigDecimal.ROUND_HALF_UP)
    def est_72hour_low = (next72Low_a + next72Low_b).setScale(2, BigDecimal.ROUND_HALF_UP)

//round values
    def rnd_peak24_a = peak24_a.pv_estimate.setScale(2, BigDecimal.ROUND_HALF_UP)
    def rnd_peak24_b = peak24_b.pv_estimate.setScale(2, BigDecimal.ROUND_HALF_UP)
    def rnd_peak48_a = peak48_a.pv_estimate.setScale(2, BigDecimal.ROUND_HALF_UP)
    def rnd_peak48_b = peak48_b.pv_estimate.setScale(2, BigDecimal.ROUND_HALF_UP)
    def rnd_peak72_a = peak72_a.pv_estimate.setScale(2, BigDecimal.ROUND_HALF_UP)
    def rnd_peak72_b = peak72_b.pv_estimate.setScale(2, BigDecimal.ROUND_HALF_UP)
    def rnd_next1_a = (next1_a*1000).setScale(2, BigDecimal.ROUND_HALF_UP)
    def rnd_next1_b = (next1_b*1000).setScale(2, BigDecimal.ROUND_HALF_UP)

//send events
    sendEvent(name: "24_Hour_Peak_Production_a", value: rnd_peak24_a)
    sendEvent(name: "24_Hour_Peak_Production_b", value: rnd_peak24_b)
    sendEvent(name: "48_Hour_Peak_Production_a", value: rnd_peak48_a)
    sendEvent(name: "48_Hour_Peak_Production_b", value: rnd_peak48_b)
    sendEvent(name: "72_Hour_Peak_Production_a", value: rnd_peak72_a)
    sendEvent(name: "72_Hour_Peak_Production_b", value: rnd_peak72_b)
    sendEvent(name: "1_Hour_Estimate_a", value: rnd_next1_a)
    sendEvent(name: "1_Hour_Estimate_b", value: rnd_next1_b)
    sendEvent(name: "1_Hour_Estimate", value: est_1hour)
    sendEvent(name: "one_hour_estimate", value: est_1hour)
    sendEvent(name: "24_Hour_Estimate", value: est_24hour)
    sendEvent(name: "energy", value: est_24hour)
    sendEvent(name: "power", value: est_24hour*1000)
    sendEvent(name: "24_Hour_Estimate_High", value: est_24hour_high)
    sendEvent(name: "24_Hour_Estimate_Low", value: est_24hour_low)
    sendEvent(name: "48_Hour_Estimate", value: est_48hour)
    sendEvent(name: "48_Hour_Estimate_High", value: est_48hour_high)
    sendEvent(name: "48_Hour_Estimate_Low", value: est_48hour_low)
    sendEvent(name: "72_Hour_Estimate", value: est_72hour)
    sendEvent(name: "72_Hour_Estimate_High", value: est_72hour_high)
    sendEvent(name: "72_Hour_Estimate_Low", value: est_72hour_low)

    def now = new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'")
    sendEvent(name: "lastUpdate", value: now)

//log message for totals
    if(logEnable) log.info  "{ \"next1\": " + est_1hour + ", \"next24\": " +  est_24hour + ", \"next24High\": " +  est_24hour_high + ", \"next24Low\": " + est_24hour_low  + ", \"next48\": " + est_48hour + ", \"next48High\": " + est_48hour_high + ", \"next48Low\": " + est_48hour_low + ", \"next72\": " + est_72hour + ", \"next72High\": " + est_72hour_high + ", \"next72Low\": " +  est_72hour_low + "}";

// Create Dashboard tile
    if(htmlTile) {

        //get local time for last update
        def nowlocal = String.format('%tF %<tH:%<tM:%<tS', java.time.LocalDateTime.now())

	    def html24hour ="<div style='line-height:100%; font-size:0.75em;'><br>24 Hour Estimates:<br></div>"
        html24hour +="<div style='line-height:100%; font-size:0.75em;'><br>${est_24hour_low} / ${est_24hour} / ${est_24hour_high} kWh<br></div>"
	    html24hour +="<div style='line-height:25%;'><br></div>"
	    html24hour +="<div style='line-height:100%; font-size:0.75em;'><br>Peak Array Power:<br></div>"
	    html24hour +="<div style='line-height:100%; font-size:0.75em;'><br>${rnd_peak24_a} kW / ${rnd_peak24_b} kW<br></div>"
        html24hour +="<div style='line-height:25%;'><br></div>"
        html24hour +="<div style='line-height:100%; font-size:0.75em;'><br>Updated: ${nowlocal}</div>"

        sendEvent(name: "html24hour", value: html24hour)

        if(debugLog) {log.debug "html24hour contains ${html24hour}"}
	}

    if (settings.refresh_interval != "0" && settings.randomize)
    //if randomize is selected, reset refresh time
    {
        def refreshseconds = 0
        refreshseconds = Math.abs(new Random().nextInt() % 59) +1

        if (settings.refresh_interval == "24") {
           schedule("${refreshseconds} ${settings.refreshminute} ${settings.refreshhour} ? * * *", refresh, [overwrite: true])
        } else if(settings.refresh_interval == "30"){
        schedule("${refreshseconds} */30 * ? * *", refresh, [overwrite: true])
        } else {
        schedule("${refreshseconds} ${settings.refreshminute} */${settings.refresh_interval} ? * * *", refresh, [overwrite: true])
        }
    }

}
