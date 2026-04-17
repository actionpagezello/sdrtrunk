import xmlrpc.client
server = xmlrpc.client.ServerProxy('http://localhost:8000')
server.submit()
