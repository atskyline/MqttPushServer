var pushServerEvent = require('./pushServer').event;
var bunyan = require('bunyan');
var log = bunyan.createLogger({name: "MqttPushServer"});

exports.start = function(){
  pushServerEvent.on('connect',function(client,packet){
    log.info("connect[%s]\npacket:%j",client.id,packet);
  });

  pushServerEvent.on('publish',function(client,packet){
    log.info("publish[%s]\npacket:%j",client.id,packet);
  });

  pushServerEvent.on('subscribe',function(client,packet){
    log.info("subscribe[%s]\npacket:%j",client.id,packet);
  });

  pushServerEvent.on('pingreq',function(client,packet){
    log.info("pingreq[%s]\npacket:%j",client.id,packet);
  });

  pushServerEvent.on('disconnect',function(client,packet){
    log.info("disconnect[%s]\npacket:%j",client.id,packet);
  });

  pushServerEvent.on('close',function(client,err){
    log.warn("error[%s]%j",client.id,err);
  });

  pushServerEvent.on('error',function(client,err){
    log.warn("error[%s]%j",client.id,err);
  });

  pushServerEvent.on('clean',function(client){
    log.warn("clean[%s]",client.id);
  });
}