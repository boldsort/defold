#! /usr/bin/env python

VERSION='0.1'
APPNAME='physics'

srcdir = '.'
blddir = 'build'

import sys
import waf_dynamo, waf_ddf

def init():
    pass

def set_options(opt):
    opt.sub_options('src')
    opt.tool_options('compiler_cxx')
    opt.tool_options('waf_dynamo')
    
def configure(conf):
    conf.check_tool('compiler_cxx')

    conf.check_tool('waf_dynamo')
    waf_ddf.configure(conf)

    conf.sub_config('src')
    
    conf.env.append_value('CPPPATH', "default/src")
    conf.env['LIB_GTEST'] = 'gtest'
    conf.env['STATICLIB_DLIB'] = 'dlib'

def build(bld):
    bld.add_subdirs('src')
    
import Build, Options
import os, subprocess
def shutdown():
    waf_dynamo.run_gtests(valgrind = True)
