#![feature(iterator_try_collect)]
#![feature(result_option_inspect)]

#[macro_use]
extern crate log;

#[cfg(target_os = "android")]
mod android;

#[cfg(target_os = "ios")]
mod ios;

