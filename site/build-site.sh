#!/bin/bash

if [ ! -d themes/hugo-material-docs ]
then
  git clone https://github.com/digitalcraftsman/hugo-material-docs.git themes/hugo-material-docs
  cd themes/hugo-material-docs
  git checkout 194c497216c8389e02e9719381168a668a0ffb05
  cd ../..
fi
if [ $1 ]
then
  hugo --theme hugo-material-docs -d $1
else
  hugo --theme hugo-material-docs
fi

