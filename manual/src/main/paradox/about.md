# About Otoroshi

At the beginning of 2017, we had the need to create a new environment to be able to create new "digital" products very quickly in an agile fashion at @link:[MAIF](https://www.maif.fr) { open=new }. Naturally we turned to PaaS solutions and chose the excellent @link:[Clever Cloud](https://www.clever-cloud.com) { open=new } product to run our apps. 

We also chose that every feature team will have the freedom to choose its own technological stack to build its product. It was a nice move but it has also introduced some challenges in terms of homogeneity for traceability, security, logging, ... because we did not want to force library usage in the products. We could have used something like @link:[Service Mesh Pattern](http://philcalcado.com/2017/08/03/pattern_service_mesh.html) { open=new } but the deployement model of @link:[Clever Cloud](https://www.clever-cloud.com) { open=new } prevented us to do it.

The right solution was to use a reverse proxy or some kind of API Gateway able to provide tracability, logging, security with apikeys, quotas, DNS as a service locator, etc. We needed something easy to use, with a human friendly UI, a nice API to extends its features, true hot reconfiguration, able to generate internal events for third party usage. A couple of solutions were available at that time, but not one seems to fit our needs, there was always something missing, too complicated for our needs or not playing well with @link:[Clever Cloud](https://www.clever-cloud.com) { open=new } deployment model.

At some point, we tried to write a small prototype to explore what could be our dream reverse proxy. The design was very simple, there were some rough edges but every major feature needed was there waiting to be enhanced.

**Otoroshi** was born and we decided to move ahead with our hairy monster :)

## Philosophy 

Every OSS product build at @link:[MAIF](https://www.maif.fr) { open=new } like the develoer portal @link:[Daikoku](https://maif.github.io/daikoku) { open=new } or @link:[Izanami](https://maif.github.io/izanami) { open=new } follow a common philosophy. 

* the services or API provided should be **technology agnostic**.
* **http first**: http is the right answer to the previous quote   
* **api First**: the UI is just another client of the api. 
* **secured**: the services exposed need authentication for both humans or machines  
* **event based**: the services should expose a way to get notified of what happened inside.  
