import React, { Component } from 'react';

import {
  TextInput,
  NumberInput,
  SelectInput,
  CodeInput,
  ArrayInput,
  BooleanInput,
  PasswordInput,
} from './inputs';
import { Separator } from './Separator';

import deepSet from 'set-value';
import _ from 'lodash';

class RestrictionPath extends Component {

  changeTheValue = (key, value) => {
    const arrayValue = [...this.props.value];
    const item = arrayValue[this.props.idx];
    const newItem = deepSet(item, key, value);
    arrayValue[this.props.idx] = newItem
    this.props.onChange(arrayValue);
  }

  render() {
    return (
      <div className="form-group">
        <label className="col-xs-12 col-sm-2 control-label"></label>
        <div className="col-sm-10" style={{ display: 'flex' }}>
          <input className="form-control" style={{ width: '30%' }} placeholder="Http Method" type="text" value={this.props.itemValue.method} onChange={e => this.changeTheValue('method', e.target.value)}></input>
          <input className="form-control" style={{ width: '70%' }} placeholder="Http Path" type="text" value={this.props.itemValue.path} onChange={e => this.changeTheValue('path', e.target.value)}></input>
        </div>
      </div>
    );
  }
}

export class Restrictions extends Component {
  changeTheValue = (name, value) => {
    const newValue = deepSet({ ...this.props.rawValue }, name, value)
    this.props.rawOnChange(newValue);
  };
  render() {
    const value = this.props.rawValue;
    return (
      <div>
        <BooleanInput
          label="Enabled"
          value={value.enabled}
          help="Enable restrictions"
          onChange={v => this.changeTheValue('enabled', v)}
        />
        <BooleanInput
          label="Allow last"
          value={value.allowLast}
          help="Otoroshi will test forbidden and notFound paths before testing allowed paths"
          onChange={v => this.changeTheValue('allowLast', v)}
        />
        <ArrayInput
          label="Allowed"
          value={value.allowed}
          help="Allowed paths"
          component={RestrictionPath}
          defaultValue={{ method: '*', path: '/.*'}}
          onChange={v => this.changeTheValue('allowed', v)}
        />
        <ArrayInput
          label="Forbidden"
          value={value.forbidden}
          help="Forbidden paths"
          component={RestrictionPath}
          defaultValue={{ method: '*', path: '/.*'}}
          onChange={v => this.changeTheValue('forbidden', v)}
        />
        <ArrayInput
          label="Not Found"
          value={value.notFound}
          help="Not found paths"
          component={RestrictionPath}
          defaultValue={{ method: '*', path: '/.*'}}
          onChange={v => this.changeTheValue('notFound', v)}
        />
      </div>
    );
  }
}