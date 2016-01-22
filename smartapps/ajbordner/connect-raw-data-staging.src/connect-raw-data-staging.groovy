/**
 *  Connect Web Service
 *
 *  Copyright 2015 linkAges Connect
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
definition(
    name: "Connect Raw Data Staging",
    namespace: "ajbordner",
    author: "linkAges Connect",
    description: "Web Service for downloading the sensor event history for Connect users",
    category: "Health & Wellness",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    oauth: [displayName: "Palo Alto Medical Foundation", displayLink: "http://connect.linkages.org"])

preferences {
  section ("Allow linkAges Connect to access these things...") {
    input "switches", "capability.powerMeter", title: "Outlet", multiple: true, required: true
    input "acceleration", "capability.accelerationSensor", title: "Accelerometer", multiple: true, required: true
    input "contact", "capability.contactSensor", title: "Contact sensor", multiple: true, required: true
    input "motion", "capability.motionSensor", title: "Motion sensor", multiple: true, required: true
    input "water", "capability.waterSensor", title: "Water sensor", multiple: true, required: false
    input "presence", "capability.presenceSensor", title: "Presence sensor", multiple: true, required: false
    input "batteries", "capability.battery", title: "Batteries", multiple: true, required: false
  }
}

mappings {
   path("/getHistory") {
    action: [
      GET: "listEvents"
    ]
  }
   path("/setPowerCutoff") {
    action: [
      GET: "setPowerCutoff"
    ]
  }
   path("/pushEvents") {   // manually push event data
    action: [
     GET: "postEventsHandler"
    ]
  }
  path("/setUpdateInterval") {
    action: [
     GET: "setUpdateInterval"
    ]
  }
  path("/getUpdateInterval") {
    action: [
     GET: "getUpdateInterval"
    ]
  }
  path("/getBatteryLevels") {
    action: [
     GET: "listBatteries"
    ]
  }
  path("/getStateVars") {
    action: [
     GET: "getStateVars"
    ]
  }
  path("/getLatestEventTimes") {
    action: [
     GET: "getLatestEventTimes"
    ]
  }
    path("/getAppName") {
    action: [
     GET: "getAppName"
    ]
  }

}

def listEvents() {
	def maxNumEvents = 99999
    def startDate = new Date().parse("yyyy-MM-dd", params?.startDate)
    // event data for required sensors
 	def allEvents = contact.eventsSince(startDate, [max: maxNumEvents]) + motion.eventsSince(startDate, [max: maxNumEvents]) + switches.eventsSince(startDate, [max: maxNumEvents])
	// event data for optional sensors
	if (water != null) {
    	allEvents = allEvents + water.eventsSince(startDate, [max: maxNumEvents])
    }
    if (presence != null) {
    	allEvents = allEvents + presence.eventsSince(startDate, [max: maxNumEvents])
    }
   	def rawData = []
    allEvents.each { dev ->
    	dev.each {
        //log.debug "name = ${it.device?.name}"
        //log.debug "label = ${it.device?.label}"
        //log.debug "event_type = ${it?.name}"
        //log.debug "value = ${it?.value}"
        //log.debug "date = ${it?.isoDate}"
        if (it?.name != null && it?.name != "temperature") {
        	rawData << [name: it.device?.name, label : it.device?.label, event_type: it?.name, value: it?.value, date: it?.isoDate]
         }
    		//rawData << [label: it?.displayName, name: it?.name, value: it?.value, date: it?.isoDate]
        }
    }
    def resp = ["hub_id": motion.hub.zigbeeId[0], "location": location.name, "raw": rawData]
    return resp
}

def listBatteries() {
	return ["hub_id": motion.hub.zigbeeId[0], "location": location.name,"info": batteries?.collect{[id: it.id, name: it.name, label: it.label, status: it.currentValue('battery')]}?.sort{it.name}]
}

def getStateVars() {
	return [state: state]
}

def getAppName() {
	return [name: app.label]
}

def installed() {
	log.debug "Installed with settings: ${settings}"

	initialize()
}

def updated() {
	log.debug "Updated settings: ${settings}"

	unsubscribe()
	initialize()
}

def initialize() {
    subscribe(switches, "power", switchHandler)
    subscribe(acceleration, "acceleration", accelerationHandler)
    subscribe(contact, "contact", contactHandler)
    subscribe(motion, "motion", motionHandler)
    subscribe(water, "water", waterHandler)
    subscribe(presence, "presence", presenceHandler)
    state.rawEvtData = []
    state.lastActivity = ['Motion Sensor': [null, 'inactive'], 'Multipurpose A contact': [null, 'closed'], 'Multipurpose A acceleration': [null, 'active'], 'Multipurpose B contact': [null, 'closed'], 'Multipurpose B acceleration': [null, 'inactive'], 'Arrival Sensor': [null, 'not_present']]
	state.maxPower = 0
	state.outletUpdateInterval = 60
    state.outletChangeTime = (new Date()).time
    state.cronMinutes = 1  // every minute
    state.cronSeconds = (new Random()).nextInt(60);
    schedule("${state.cronSeconds} 0/${state.cronMinutes} * * * ?", postEventsHandler)
}

def setUpdateInterval() {
  state.cronMinutes = params?.minutes.toInteger()
  unschedule()
  schedule("${state.cronSeconds} 0/${state.cronMinutes} * * * ?", postEventsHandler)
}

def getUpdateInterval() {
  return [minutes: state.cronMinutes, seconds: state.cronSeconds]
}

def addRawEvent(evt) {
  if (evt.name == "temperature") {
  	return []
  }
  state.rawEvtData << [name: evt.device.name, label : evt.device.label, event_type: evt.name, value: evt.value, date: evt.isoDate]
}

def addOutletEvent(evt) {
  def now = (new Date()).time 
  def value = evt.value
  def timeSinceChange = (now - state.outletChangeTime)/1000.0
  state.maxPower = Math.max(state.maxPower, Float.parseFloat(evt.value))
  if (timeSinceChange >= state.outletUpdateInterval) {
    state.rawEvtData << [name: evt.device.name, label : evt.device.label, event_type: evt.name, value: sprintf("%.1f",state.maxPower), date: evt.isoDate]
    state.outletChangeTime = now
    state.maxPower = 0
  }
}

def getLatestEventTimes() {
	def resp = [(switches.name[0]): [switches.latestState("power").dateCreated[0], switches.latestState("power").value[0]]]
    for (dev in state.lastActivity) {
    	resp << dev
    }
	return resp
}

def postEventsHandler() {
     // Push if any processed event data
     def json = new groovy.json.JsonBuilder(["hub_id": motion.hub.zigbeeId[0], "location": location.name, "raw": state.rawEvtData]).toString()
    //log.debug("JSON: ${json}")
    def params = [
   	uri: "https://connect.linkages.org/smartthings_raw.php",
    	body: json
   	]
    /*
    try {
        httpPostJson(params)
    } catch ( groovyx.net.http.HttpResponseException ex ) {
       	log.debug "Unexpected response error for Connect production: ${ex.statusCode}"
    }
    */
    params.uri = "https://pamf-connect-propane.linkages.org/smartthings_raw.php"  // also post data to staging
    try {
        httpPostJson(params)
    } catch ( groovyx.net.http.HttpResponseException ex ) {
       	log.debug "Unexpected response error for Connect staging: ${ex.statusCode}"
    }
    state.rawEvtData = []  // clear raw event data
}

def switchHandler(evt) {
    addOutletEvent(evt)
}

def accelerationHandler(evt) {
    addRawEvent(evt)
    if (evt.value == "active") {
    	state.lastActivity["${evt.device.name} acceleration"] = [new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC")), evt.value]
    }
}

def contactHandler(evt) {
    addRawEvent(evt)
    if (evt.value == "closed") {
    	state.lastActivity["${evt.device.name} contact"] = [new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC")), evt.value]
    }
}

def motionHandler(evt) {
    addRawEvent(evt)
    if (evt.value == "active") {
    	state.lastActivity[evt.device.name] = [new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC")), evt.value]
    }
}

def waterHandler(evt) {
    addRawEvent(evt)
    state.lastActivity[evt.device.name] = [new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC")), evt.value]
}

def presenceHandler(evt) {
    addRawEvent(evt)
    state.lastActivity[evt.device.name] = [new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC")), evt.value]
}
