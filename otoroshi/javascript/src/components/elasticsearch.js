import React, { Component } from 'react';

export class CheckElasticsearchConnection extends Component {

  checkConnection = () => {
    fetch('/bo/api/elastic/_check', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(this.props.rawValue)
    }).then(r => r.json()).then(r => {
      console.log(r);
      if (r.none) {
        window.newAlert('Missing informations to make the connection to the Elasticsearch cluster')
      } else {
        window
          .popup(
            'Elasticsearch connection',
            (ok, cancel) => <ElasticsearchConnectionDiagnostic ok={ok} cancel={cancel} resp={r} spec={this.props.rawValue} />
          )
      }
    }); 
  }

  render() {
    return (
      <div className="form-group">
        <label className="col-sm-2 control-label"></label>
        <div className="col-sm-10">
          <button className="btn btn-sm btn-success" type="button" onClick={this.checkConnection}>Check connection !</button>
        </div>
      </div>
    );
  }
}

class ElasticsearchConnectionDiagnostic extends Component {
  render() {
    const works = (!this.props.resp.search.error && !this.props.resp.version.error)
    return (
      <>
        <div className="modal-body">
          {this.props.resp.version.error && <>Elasticsearch version: <span className="label label-danger">data not available</span></>}
          {!this.props.resp.version.error && <>Elasticsearch version: <span className="label label-success">{this.props.resp.version}</span></>}
          <br/>
          {this.props.resp.search.error && <>Elasticsearch search API: <span className="label label-danger">data not available</span></>}
          {!this.props.resp.search.error && <>Elasticsearch search API: <span className="label label-success">{this.props.resp.search} docs</span></>}
          {works && (
            <p style={{ marginTop: 20 }}>
              Connection to the Elasticsearch cluster works fine !
            </p>
          )}

          {this.props.spec.applyTemplate && this.props.resp.version.error && (
            <p style={{ marginTop: 20 }}>Unable to access Elasticsearch version. Maybe you don't have the rights to access it. It's needed to automatically apply otoroshi index template</p>
          )}
          {this.props.resp.search.error && (
            <p style={{ marginTop: 20 }}>Unable to access Elasticsearch search api on your index <span className="label label-default">{this.props.spec.index}</span>. Maybe you don't have the rights to access it.</p>
          )}
        </div>
        <div className="modal-footer">
          <button type="button" className="btn btn-danger" onClick={this.props.cancel}>
            Close
          </button>
        </div>
      </>
    );
  }
}