package com.inductiveautomation.opcua.stack.core.types.structured;

import com.inductiveautomation.opcua.stack.core.Identifiers;
import com.inductiveautomation.opcua.stack.core.serialization.DelegateRegistry;
import com.inductiveautomation.opcua.stack.core.serialization.UaDecoder;
import com.inductiveautomation.opcua.stack.core.serialization.UaEncoder;
import com.inductiveautomation.opcua.stack.core.serialization.UaResponseMessage;
import com.inductiveautomation.opcua.stack.core.types.builtin.DiagnosticInfo;
import com.inductiveautomation.opcua.stack.core.types.builtin.NodeId;

public class AddNodesResponse implements UaResponseMessage {

    public static final NodeId TypeId = Identifiers.AddNodesResponse;
    public static final NodeId BinaryEncodingId = Identifiers.AddNodesResponse_Encoding_DefaultBinary;
    public static final NodeId XmlEncodingId = Identifiers.AddNodesResponse_Encoding_DefaultXml;

    protected final ResponseHeader _responseHeader;
    protected final AddNodesResult[] _results;
    protected final DiagnosticInfo[] _diagnosticInfos;

    public AddNodesResponse(ResponseHeader _responseHeader, AddNodesResult[] _results, DiagnosticInfo[] _diagnosticInfos) {
        this._responseHeader = _responseHeader;
        this._results = _results;
        this._diagnosticInfos = _diagnosticInfos;
    }

    public ResponseHeader getResponseHeader() { return _responseHeader; }

    public AddNodesResult[] getResults() { return _results; }

    public DiagnosticInfo[] getDiagnosticInfos() { return _diagnosticInfos; }

    @Override
    public NodeId getTypeId() { return TypeId; }

    @Override
    public NodeId getBinaryEncodingId() { return BinaryEncodingId; }

    @Override
    public NodeId getXmlEncodingId() { return XmlEncodingId; }


    public static void encode(AddNodesResponse addNodesResponse, UaEncoder encoder) {
        encoder.encodeSerializable("ResponseHeader", addNodesResponse._responseHeader);
        encoder.encodeArray("Results", addNodesResponse._results, encoder::encodeSerializable);
        encoder.encodeArray("DiagnosticInfos", addNodesResponse._diagnosticInfos, encoder::encodeDiagnosticInfo);
    }

    public static AddNodesResponse decode(UaDecoder decoder) {
        ResponseHeader _responseHeader = decoder.decodeSerializable("ResponseHeader", ResponseHeader.class);
        AddNodesResult[] _results = decoder.decodeArray("Results", decoder::decodeSerializable, AddNodesResult.class);
        DiagnosticInfo[] _diagnosticInfos = decoder.decodeArray("DiagnosticInfos", decoder::decodeDiagnosticInfo, DiagnosticInfo.class);

        return new AddNodesResponse(_responseHeader, _results, _diagnosticInfos);
    }

    static {
        DelegateRegistry.registerEncoder(AddNodesResponse::encode, AddNodesResponse.class, BinaryEncodingId, XmlEncodingId);
        DelegateRegistry.registerDecoder(AddNodesResponse::decode, AddNodesResponse.class, BinaryEncodingId, XmlEncodingId);
    }

}