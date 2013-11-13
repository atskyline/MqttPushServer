var pushServerEvent = require('./pushServer').event;
var util = require('util');

exports.start = function(io){
  pushServerEvent.on('connect',function(client,packet){
    io.sockets.send(util.format("connect[%s]\npacket:%j",client.id,packet));
  });

  pushServerEvent.on('publish',function(client,packet){
    io.sockets.send(util.format("publish[%s]\npacket:%j",client.id,packet));
  });

  pushServerEvent.on('subscribe',function(client,packet){
    io.sockets.send(util.format("subscribe[%s]\npacket:%j",client.id,packet));
  });

  pushServerEvent.on('pingreq',function(client,packet){
    io.sockets.send(util.format("pingreq[%s]\npacket:%j",client.id,packet));
  });

  pushServerEvent.on('disconnect',function(client,packet){
    io.sockets.send(util.format("disconnect[%s]\npacket:%j",client.id,packet));
  });

  pushServerEvent.on('close',function(client,err){
    io.sockets.send(util.format("error[%s]%j",client.id,err));
  });

  pushServerEvent.on('error',function(client,err){
    io.sockets.send(util.format("error[%s]%j",client.id,err));
  });


  pushServerEvent.on('clean',function(client){
    io.sockets.send(util.format("clean[%s]",client.id));
  });
}