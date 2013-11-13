var express = require('express');
var http = require('http');
var path = require('path');
var pushServer = require('./pushServer');
var consoleLoger = require('./consoleLogger');
var socketIoLogger = require('./socketIoLogger');

var app = express();
var server = http.createServer(app);
var io = require('socket.io').listen(server);

var users = require('./users');
var client = require('./pushClient');

// all environments
app.set('port', process.env.PORT || 3000);
app.use(express.favicon());
app.use(express.logger('dev'));
app.use(express.bodyParser());
app.use(express.methodOverride());
app.use(app.router);
app.use(express.static(path.join(__dirname, 'public')));

// development only
if ('development' == app.get('env')) {
  app.use(express.errorHandler());
}

//interface
app.get('/users',users.get);
app.post('/push',client.push);

//start web Server
server.listen(app.get('port'), function(){
  console.log('Express server listening on port ' + app.get('port'));
});
//start push server
pushServer.start(1883);

//logger
socketIoLogger.start(io);
consoleLoger.start();
