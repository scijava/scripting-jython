#!/bin/sh
curl -fsLO https://raw.githubusercontent.com/scijava/scijava-scripts/master/travis-build.sh
sh travis-build.sh $encrypted_340118958087_key $encrypted_340118958087_iv
