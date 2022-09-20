import React from 'react';
import GraphQLForm from './GraphQLForm';
import MocksDesigner from './MocksDesigner';

export const PLUGIN_INFORMATIONS_SCHEMA = {
  enabled: {
    visibleOnCollapse: true,
    type: 'bool',
    label: 'Enabled',
  },
  debug: {
    type: 'bool',
    label: 'Debug',
  },
  include: {
    label: 'Include',
    format: 'singleLineCode',
    type: 'string',
    array: true,
    createOption: true,
  },
  exclude: {
    label: 'Exclude',
    format: 'singleLineCode',
    type: 'string',
    array: true,
    createOption: true,
  },
};

export const EXCLUDED_PLUGINS = {
  plugin_visibility: ['internal'],
  ids: ['otoroshi.next.proxy.ProxyEngine'],
};

export const LEGACY_PLUGINS_WRAPPER = {
  app: 'otoroshi.next.plugins.wrappers.RequestTransformerWrapper',
  transformer: 'otoroshi.next.plugins.wrappers.RequestTransformerWrapper',
  validator: 'otoroshi.next.plugins.wrappers.AccessValidatorWrapper',
  preroute: 'otoroshi.next.plugins.wrappers.PreRoutingWrapper',
  sink: 'otoroshi.next.plugins.wrappers.RequestSinkWrapper',
  composite: 'otoroshi.next.plugins.wrappers.CompositeWrapper',
  listener: '',
  job: '',
  exporter: '',
  'request-handler': '',
};

export const PLUGINS = {
  'cp:otoroshi.next.tunnel.TunnelPlugin': (plugin) => ({
    //...plugin,
    type: 'object',
    label: null,
    format: 'form',
    flow: ['tunnel_id'],
    schema: {
      //...(plugin || {}).schema,
      tunnel_id: {
        label: 'Tunnel ID',
        type: 'string',
      },
    },
  }),
  'cp:otoroshi.next.plugins.SOAPAction': (plugin) => ({
    ...plugin,
    schema: {
      ...plugin.schema,
      envelope: {
        label: 'envelope',
        type: 'string',
        format: 'code',
      },
    },
  }),
  'cp:otoroshi.next.plugins.SOAPActionConfig': (plugin) => ({
    ...plugin,
    schema: {
      ...plugin.schema,
      envelope: {
        label: 'envelope',
        type: 'string',
        format: 'code',
      },
    },
  }),
  'cp:otoroshi.next.plugins.GraphQLBackend': (plugin, showAdvancedDesignerView) => ({
    ...plugin,
    schema: {
      turn_view: {
        type: 'bool',
        label: null,
        defaultValue: false,
        render: () => (
          <button
            type="button"
            className="btn btn-sm btn-success me-3 mb-3"
            onClick={() => showAdvancedDesignerView(GraphQLForm)}>
            Expand
          </button>
        ),
      },
      permissions: {
        type: 'string',
        array: true,
        label: 'Permissions paths',
      },
      ...plugin.schema,
    },
    flow:
      plugin.flow.indexOf('permissions') > -1
        ? ['turn_view', ...plugin.flow]
        : ['turn_view', ...plugin.flow, 'permissions'],
  }),
  'cp:otoroshi.next.plugins.MockResponses': (plugin, showAdvancedDesignerView) => ({
    ...plugin,
    schema: {
      turn_view: {
        type: 'bool',
        label: null,
        defaultValue: false,
        render: (props) => {
          return (
            <button
              type="button"
              className="btn btn-sm btn-success me-3 mb-3"
              onClick={() => showAdvancedDesignerView(MocksDesigner)}>
              Expand
            </button>
          );
        },
      },
      form_data: {
        ...plugin.schema.form_data,
        visible: false,
      },
      ...plugin.schema,
    },
    flow: ['turn_view', ...plugin.flow],
  }),
};

export const DEFAULT_FLOW = {
  Frontend: {
    id: 'Frontend',
    icon: 'user',
    plugin_steps: [],
    description: null,
    field: 'frontend',
    config_schema: {
      domains: {
        type: 'string',
        array: true,
        label: 'Domains',
      },
      methods: {
        type: 'array-select',
        props: {
          label: 'Methods',
          options: ['GET', 'HEAD', 'POST', 'PUT', 'DELETE', 'OPTIONS', 'PATCH']
            .map(item => ({ label: item, value: item }))
        }
      },
    },
    config_flow: ['domains', 'strip_path', 'exact', 'headers', 'methods', 'query'],
  },
  Backend: (parentNode) => ({
    id: 'Backend',
    icon: 'bullseye',
    group: 'Targets',
    field: 'backend',
    config_schema: (generatedSchema) => ({
      ...generatedSchema,
      targets: {
        ...generatedSchema.targets,
        onAfterChange: ({ onChange, getFieldValue }) => {
          let port = getFieldValue('port');
          port = port ? `:${port}` : '';
          const hostname = getFieldValue('hostname') || '';
          const isSecured = getFieldValue('tls');

          onChange(
            'custom_target',
            `${isSecured ? 'https' : 'http'}://${hostname}${port}${getFieldValue('custom_target')?.endsWith(' ') ? ' ' : ''
            }`
          );
        },
        schema: {
          custom_target: {
            label: 'Target',
            type: 'string',
            disabled: true,
            render: ({ value, onChange }) => {
              const open = value.endsWith(' ');
              return (
                <div
                  className="d-flex-center justify-content-start target_information mt-3"
                  onClick={() => onChange(open ? value.slice(0, -1) : `${value} `)}>
                  <i className={`me-2 fas fa-chevron-${open ? 'down' : 'right'}`} />
                  <i className="fas fa-server me-2" />
                  <a>{value}</a>
                </div>
              );
            },
          },
          ...Object.fromEntries(
            Object.entries(generatedSchema.targets.schema).map(([key, value]) => [
              key,
              {
                ...value,
                visible: {
                  ref: parentNode,
                  test: (v, idx) => {
                    return !!v.targets[idx]?.custom_target.endsWith(' ');
                  },
                },
              },
            ])
          ),
          hostname: {
            ...generatedSchema.targets.schema.hostname,
            visible: {
              ref: parentNode,
              test: (v, idx) => !!v.targets[idx]?.custom_target.endsWith(' '),
            },
            constraints: [
              {
                type: 'blacklist',
                arrayOfValues: ['http:', 'https:', 'tcp:', 'udp:', '/'],
                message: 'You cannot use protocol scheme or / in the Host name',
              },
            ],
          },
        },
        flow: ['custom_target', ...generatedSchema.targets.flow],
      },
    }),
    config_flow: [
      'root',
      'targets',
      'health_check',
      'target_refs',
      'client',
      'rewrite',
      'load_balancing',
    ],
  }),
};
