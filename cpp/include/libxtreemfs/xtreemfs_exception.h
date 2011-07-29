/*
 * Copyright (c) 2011 by Michael Berlin, Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */

#ifndef CPP_INCLUDE_LIBXTREEMFS_XTREEMFS_EXCEPTION_H_
#define CPP_INCLUDE_LIBXTREEMFS_XTREEMFS_EXCEPTION_H_

#include <boost/cstdint.hpp>
#include <stdexcept>
#include <string>

namespace xtreemfs {

class XtreemFSException : public std::runtime_error {
 public:
  explicit XtreemFSException(const std::string& msg)
    : std::runtime_error(msg) {}
};

class PosixErrorException : public XtreemFSException {
 public:
  PosixErrorException(int posix_errno, const std::string& msg)
    : XtreemFSException(msg),
      posix_errno_(posix_errno)  {}
  int posix_errno() const { return posix_errno_; }
 private:
  int posix_errno_;
};

/** Will be thrown, if there was an IO_ERROR in the RPC Client on the client
 *  side. */
class IOException : public XtreemFSException {
 public:
  IOException() : XtreemFSException("IOError occured.") {}
  explicit IOException(const std::string& msg)
    : XtreemFSException(msg) {}
};

/** Will be thrown if the server did return an INTERNAL_SERVER_ERROR. */
class InternalServerErrorException : public XtreemFSException {
 public:
  InternalServerErrorException()
   : XtreemFSException("Internal Server Error received.") {}
  explicit InternalServerErrorException(const std::string& msg)
    : XtreemFSException(msg) {}
};

/** Thrown if FileInfo for given file_id was not found in OpenFileTable.
 *
 * Every FileHandle does reference a FileInfo object where per-file properties
 * are stored. This exception should never occur as it means there was no
 * FileInfo for the FileHandle.
 */
class FileInfoNotFoundException : public XtreemFSException {
 public:
  explicit FileInfoNotFoundException(boost::uint64_t file_id)
    : XtreemFSException("The FileInfo object was not found in the OpenFileTable"
        " for the FileId: " + file_id) {}
};

/** Thrown if FileHandle for given file_id was not found in FileHandleList. */
class FileHandleNotFoundException : public XtreemFSException {
 public:
  explicit FileHandleNotFoundException()
    : XtreemFSException("The FileHandle object was not found in the "
        "FileHandleList") {}
};

class AddressToUUIDNotFoundException : public XtreemFSException {
 public:
  explicit AddressToUUIDNotFoundException(const std::string& uuid)
    : XtreemFSException("Address for UUID not found: " + uuid) {}
};

class VolumeNotFoundException : public XtreemFSException {
 public:
  explicit VolumeNotFoundException(const std::string& volume_name)
    : XtreemFSException("Volume not found: " + volume_name) {}
};

class OpenFileHandlesLeftException : public XtreemFSException {
 public:
  OpenFileHandlesLeftException() : XtreemFSException("There are remaining open "
      "FileHandles which have to be closed first.") {}
};

/** Thrown if the DIR Service did return a AddressMapping which is not known. */
class UnknownAddressSchemeException : public XtreemFSException {
 public:
  explicit UnknownAddressSchemeException(const std::string& msg)
    : XtreemFSException(msg) {}
};

/** Thrown if a given UUID was not found in the xlocset of a file. */
class UUIDNotInXlocSetException : public XtreemFSException {
 public:
  explicit UUIDNotInXlocSetException(const std::string& msg)
    : XtreemFSException(msg) {}
};

/** Thrown in case the OSD did reply with a redirect error, internal use only.*/
class ReplicationRedirectionException : public XtreemFSException {
 public:
  /** UUID of the actual master we were redirected to. */
  std::string redirect_to_server_uuid_;

  explicit ReplicationRedirectionException(const std::string& redirect_uuid)
    : XtreemFSException("ReplicationRedirectionException thrown (libxtreemfs "
              "internal use only - should not have shown up"),
      redirect_to_server_uuid_(redirect_uuid) {}
  /** Define a destructor with "throw()" to avoid the error message
   *  "Looser throw specifier" */
  virtual ~ReplicationRedirectionException() throw() {};
};

/** Thrown if the given URL was not parsed correctly. */
class InvalidURLException : public XtreemFSException {
 public:
  explicit InvalidURLException(const std::string& msg)
    : XtreemFSException(msg) {}
};

class InvalidCommandLineParametersException : public XtreemFSException {
 public:
  explicit InvalidCommandLineParametersException(const std::string& msg)
    : XtreemFSException(msg) {}
};

}  // namespace xtreemfs

#endif  // CPP_INCLUDE_LIBXTREEMFS_XTREEMFS_EXCEPTION_H_